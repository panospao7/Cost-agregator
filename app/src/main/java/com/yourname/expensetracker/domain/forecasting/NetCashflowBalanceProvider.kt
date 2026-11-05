package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.dao.CurrentUserSettlementRow
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.GroupCashFlowReader
import com.yourname.expensetracker.data.repository.GroupCashFlowSnapshot
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

        // One group snapshot feeds both the withdrawal total and the adjustment, so they agree on
        // which rows are non-payer or payer. A failed read is unavailable (null), never zero.
        val groupSnapshot = groupCashFlowReader.read(ninetyDaysAgo, now, "NetCashflowBalanceProvider.currentBalance")
        if (groupSnapshot.anyReadFailed) {
            Timber.w("NET_CASHFLOW_BALANCE_UNAVAILABLE reason=GROUP_ADJUSTMENT_UNAVAILABLE")
            return null
        }

        val withdrawals = withdrawalTotalOrNull(ninetyDaysAgo, now, homeCurrency, groupSnapshot)
        if (withdrawals == null) {
            Timber.w("NET_CASHFLOW_BALANCE_UNAVAILABLE reason=WITHDRAWALS_UNAVAILABLE")
            return null
        }

        // DR-01: agree with CashFlowCalculator's group rule. The totals above count a non-payer
        // row at its share and a payer row at its share; cash flow drops the former and counts the
        // latter at the full paid amount, and settlements are the cash event. Basis: purchases are
        // adjusted at LATEST rates (their totals' basis), withdrawals are already handled at date
        // rates inside the withdrawal total (not adjusted here), settlements at LATEST rates.
        // Any failure is null.
        val groupAdjustment = groupAdjustmentOrNull(ninetyDaysAgo, now, homeCurrency, groupSnapshot)
        if (groupAdjustment == null) {
            Timber.w("NET_CASHFLOW_BALANCE_UNAVAILABLE reason=GROUP_ADJUSTMENT_UNAVAILABLE")
            return null
        }
        // P-06-4: with no deposit, purchase or withdrawal row in the window there is no cashflow
        // to read, so the net is not a balance. Non-payer rows are removed from cash flow by the
        // adjustment, so they do not count as cash rows (one non-payer row of 50 and nothing else
        // would otherwise read as a real 0.0: 0 - 50 + 50).
        val cashRowCount = deposits.totalTransactionCount + purchases.totalTransactionCount +
            withdrawals.second - groupAdjustment.removedCashRowCount
        if (cashRowCount <= 0) {
            Timber.w("NET_CASHFLOW_BALANCE_UNAVAILABLE reason=NO_CASHFLOW_ROWS")
            return null
        }
        val net = deposits.displayAmount - purchases.displayAmount - withdrawals.first + groupAdjustment.amount
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
     *
     * Rate basis: withdrawals are totalled at DATE rates (via the normalizer) and the group rule is
     * applied here, entirely, so [groupAdjustmentOrNull] does not touch withdrawals. Per
     * [GroupCashFlowRule], NON_PAYER withdrawal rows are removed, PAYER rows count at the full paid
     * amount, REGULAR rows are unchanged. The rule is applied before the not-mine filter and before
     * normalizing, so a dropped non-payer row with no rate cannot withhold the balance.
     */
    private suspend fun withdrawalTotalOrNull(
        startDate: Long,
        endDate: Long,
        homeCurrency: String,
        snapshot: GroupCashFlowSnapshot
    ): Pair<Double, Int>? {
        val normalized = CancellationSafe.runCatchingCancellable {
            analyticsCurrencyNormalizer.normalizeExpenses(
                // Only WITHDRAWAL rows may affect this total. Filter before normalizing so an
                // unconvertible row of another type (for example a transfer with no rate) cannot
                // exclude itself and withhold the forecast.
                expenseRepository.getExpensesBetween(startDate, endDate)
                    .filter { it.transactionType == TransactionType.WITHDRAWAL }
                    .mapNotNull { row ->
                        when (
                            GroupCashFlowRule.classify(row.id, snapshot.nonPayerExpenseIds, snapshot.payerExpenseIds)
                        ) {
                            GroupCashFlowRule.RowClass.NON_PAYER -> null
                            GroupCashFlowRule.RowClass.PAYER -> GroupCashFlowRule.asFullAmountCopy(row)
                            else -> row
                        }
                    }
                    .filter { !it.isNotMine },
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
     * converted share) of payer rows, + received settlements, - paid settlements. Only PURCHASE
     * rows are adjusted here: withdrawals are handled entirely in [withdrawalTotalOrNull] at date
     * rates. Same half-open window as the totals and the group reads. Null when any row or
     * settlement cannot be converted (never treated as zero). The snapshot is read once by the
     * caller and its failure is handled there.
     *
     * Rate basis: the plain purchase total (getHomeCurrencyPurchaseTotal) converts every
     * per-currency bucket at the LATEST available rate. The add-back MUST use that same basis, or a
     * non-payer share of $1000 (latest 1.00, date rate 0.90) is included at EUR 1000 but removed at
     * EUR 900, leaving a phantom EUR 100 outflow. So purchases are adjusted at LATEST_AVAILABLE
     * (their totals' basis), withdrawals at date rates in the withdrawal total, and settlements
     * (added to a net made of latest-rate totals) at LATEST_AVAILABLE.
     */
    private suspend fun groupAdjustmentOrNull(
        startDate: Long,
        endDate: Long,
        homeCurrency: String,
        snapshot: GroupCashFlowSnapshot
    ): GroupAdjustment? {
        if (snapshot.nonPayerExpenseIds.isEmpty() && snapshot.payerExpenseIds.isEmpty() &&
            snapshot.settlements.isEmpty()
        ) {
            return GroupAdjustment(amount = 0.0, removedCashRowCount = 0)
        }
        val rows = CancellationSafe.runCatchingCancellable {
            expenseRepository.getExpensesBetween(startDate, endDate)
        }.getOrNull() ?: return null

        val inTotals = rows.filter { it.transactionType == TransactionType.PURCHASE }
        val nonPayerRows = inTotals.filter {
            GroupCashFlowRule.classify(it.id, snapshot.nonPayerExpenseIds, snapshot.payerExpenseIds) ==
                GroupCashFlowRule.RowClass.NON_PAYER
        }
        val nonPayerShare = convertedEffectiveTotalOrNull(nonPayerRows, homeCurrency) ?: return null
        // Non-payer PURCHASE rows that the plain totals counted as cash rows (same predicate as the
        // totals: not-mine rows are never counted, and a spending-excluded PURCHASE is never
        // counted). Non-payer withdrawals never enter the withdrawal row count.
        val removedCashRowCount = nonPayerRows.count {
            !it.isNotMine && it.spendingExclusionReason == null
        }
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
        return GroupAdjustment(
            amount = nonPayerShare - (payerFull - payerShare) + settlementNet,
            removedCashRowCount = removedCashRowCount
        )
    }

    /** Net group adjustment to the plain totals plus the non-payer rows that left the cash rows. */
    private data class GroupAdjustment(val amount: Double, val removedCashRowCount: Int)

    /**
     * Sum of home-currency effective amounts at the LATEST available rate (the totals' basis), or
     * null when any currency cannot be converted. Amounts are summed per currency first and each
     * currency bucket is converted once, as the plain totals do. Never raw-sums across currencies.
     */
    private suspend fun convertedEffectiveTotalOrNull(
        rows: List<com.yourname.expensetracker.data.database.entity.Expense>,
        homeCurrency: String
    ): Double? {
        if (rows.isEmpty()) return 0.0
        // Per-currency sums go through MoneyAggregateBuilder (same-currency bucket, home-currency
        // label only for the builder). A throw is unavailable (null); cancellation is rethrown.
        val byCurrency = CancellationSafe.runCatchingCancellable {
            rows.groupBy { it.currency.uppercase() }
                .mapValues { (currency, group) ->
                    MoneyAggregateBuilder.fromHomeCurrencyAmounts(
                        amounts = group.map { it.effectiveAmount },
                        homeCurrency = currency
                    ).displayAmount
                }
        }.getOrNull() ?: return null
        var total = 0.0
        for ((currency, amount) in byCurrency) {
            total += latestHomeAmountOrNull(amount, currency, homeCurrency) ?: return null
        }
        return total
    }

    private suspend fun latestHomeAmountOrNull(amount: Double, currency: String, homeCurrency: String): Double? {
        if (currency.equals(homeCurrency, ignoreCase = true)) return amount
        val outcome = CancellationSafe.runCatchingCancellable {
            currencyConverter.convertOutcome(
                amount = amount,
                fromCurrency = currency,
                toCurrency = homeCurrency,
                rateBasis = RateBasis.LATEST_AVAILABLE,
                atMillis = null,
                stalePolicy = StaleRatePolicy.forBasis(RateBasis.LATEST_AVAILABLE)
            )
        }.getOrNull() ?: return null
        return when (outcome) {
            is ConversionOutcome.Converted -> outcome.convertedAmount
            is ConversionOutcome.Failed -> null
        }
    }

    /** Settlement amount in the home currency at the LATEST rate (same basis as the totals), or null on failure. */
    private suspend fun settlementHomeAmountOrNull(
        settlement: CurrentUserSettlementRow,
        homeCurrency: String
    ): Double? {
        return latestHomeAmountOrNull(settlement.amount, settlement.currency.uppercase(), homeCurrency)
    }
}
