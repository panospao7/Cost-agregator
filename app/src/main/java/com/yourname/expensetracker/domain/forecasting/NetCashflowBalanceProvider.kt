package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.dao.CurrentUserSettlementRow
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.GroupCashFlowReader
import com.yourname.expensetracker.domain.cashflow.GroupCashFlowRule
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.StaleRatePolicy
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
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
    private val timeProvider: TimeProvider,
    private val groupCashFlowReader: GroupCashFlowReader,
    private val currencyConverter: CurrencyConverter
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
        // DR-01: agree with CashFlowCalculator's group rule. The totals above count a non-payer
        // row at its share and a payer row at its share; cash flow drops the former and counts the
        // latter at the full paid amount, and settlements are the cash event. Any failure is null.
        val groupAdjustment = groupAdjustmentOrNull(ninetyDaysAgo, now, homeCurrency)
        if (groupAdjustment == null) {
            Timber.w("NET_CASHFLOW_BALANCE_UNAVAILABLE reason=GROUP_ADJUSTMENT_UNAVAILABLE")
            return null
        }
        val net = deposits.displayAmount - purchases.displayAmount - withdrawals.first + groupAdjustment
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
        if (rows.isEmpty()) return 0.0 to 0
        // Rows are already normalized to homeCurrency; aggregated via MoneyAggregateBuilder.
        // A throw from the builder is unavailable (null), never escapes; cancellation is rethrown.
        val total = CancellationSafe.runCatchingCancellable {
            MoneyAggregateBuilder.fromHomeCurrencyAmounts(
                amounts = rows.map { it.effectiveAmount },
                homeCurrency = homeCurrency
            ).displayAmount
        }.getOrNull() ?: return null
        return total to rows.size
    }

    /**
     * Net home-currency change to the plain totals so they match [GroupCashFlowRule]:
     * + converted share of non-payer rows (dropped from cash flow), - (converted full amount minus
     * converted share) of payer rows, + received settlements, - paid settlements. Only PURCHASE and
     * WITHDRAWAL rows are in the totals, so only those are adjusted. Same half-open window as the
     * totals and the group reads. Null when any read fails or any row or settlement cannot be
     * converted (never treated as zero).
     */
    private suspend fun groupAdjustmentOrNull(startDate: Long, endDate: Long, homeCurrency: String): Double? {
        val snapshot = groupCashFlowReader.read(startDate, endDate, "NetCashflowBalanceProvider.currentBalance")
        if (snapshot.anyReadFailed) return null
        if (snapshot.nonPayerExpenseIds.isEmpty() && snapshot.payerExpenseIds.isEmpty() &&
            snapshot.settlements.isEmpty()
        ) {
            return 0.0
        }
        val rows = CancellationSafe.runCatchingCancellable {
            expenseRepository.getExpensesBetween(startDate, endDate)
        }.getOrNull() ?: return null

        val inTotals = rows.filter {
            it.transactionType == TransactionType.PURCHASE || it.transactionType == TransactionType.WITHDRAWAL
        }
        val nonPayerShare = convertedEffectiveTotalOrNull(
            inTotals.filter {
                GroupCashFlowRule.classify(it.id, snapshot.nonPayerExpenseIds, snapshot.payerExpenseIds) ==
                    GroupCashFlowRule.RowClass.NON_PAYER
            },
            homeCurrency
        ) ?: return null
        val payerRows = inTotals.filter {
            GroupCashFlowRule.classify(it.id, snapshot.nonPayerExpenseIds, snapshot.payerExpenseIds) ==
                GroupCashFlowRule.RowClass.PAYER
        }
        val payerShare = convertedEffectiveTotalOrNull(payerRows, homeCurrency) ?: return null
        val payerFull = convertedEffectiveTotalOrNull(
            payerRows.map { GroupCashFlowRule.asFullAmountCopy(it) },
            homeCurrency
        ) ?: return null

        val counted = GroupCashFlowRule.countedExpenseIds(rows, snapshot.nonPayerExpenseIds)
        var settlementNet = 0.0
        for (settlement in GroupCashFlowRule.dedupeSettlements(snapshot.settlements, counted)) {
            val converted = settlementHomeAmountOrNull(settlement, homeCurrency) ?: return null
            settlementNet += if (settlement.paidByCurrentUser) -converted else converted
        }
        return nonPayerShare - (payerFull - payerShare) + settlementNet
    }

    /** Sum of home-currency effective amounts, or null when normalization fails or drops a row. */
    private suspend fun convertedEffectiveTotalOrNull(
        rows: List<com.yourname.expensetracker.data.database.entity.Expense>,
        homeCurrency: String
    ): Double? {
        if (rows.isEmpty()) return 0.0
        val normalized = CancellationSafe.runCatchingCancellable {
            analyticsCurrencyNormalizer.normalizeExpenses(rows, homeCurrency)
        }.getOrNull() ?: return null
        if (normalized.severeWarnings.isNotEmpty() || normalized.excludedCount > 0) return null
        return normalized.includedExpenses.sumOf { it.effectiveAmount }
    }

    /** Settlement amount in the home currency at its date (TRANSACTION_DATE), or null on failure. */
    private suspend fun settlementHomeAmountOrNull(
        settlement: CurrentUserSettlementRow,
        homeCurrency: String
    ): Double? {
        if (settlement.currency.equals(homeCurrency, ignoreCase = true)) return settlement.amount
        val outcome = CancellationSafe.runCatchingCancellable {
            currencyConverter.convertOutcome(
                amount = settlement.amount,
                fromCurrency = settlement.currency,
                toCurrency = homeCurrency,
                rateBasis = RateBasis.TRANSACTION_DATE,
                atMillis = settlement.createdAt,
                stalePolicy = StaleRatePolicy.forBasis(RateBasis.TRANSACTION_DATE)
            )
        }.getOrNull() ?: return null
        return when (outcome) {
            is ConversionOutcome.Converted -> outcome.convertedAmount
            is ConversionOutcome.Failed -> null
        }
    }
}
