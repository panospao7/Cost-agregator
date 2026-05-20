package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.CategoryCurrencyTotal
import com.yourname.expensetracker.data.database.dao.CurrencyTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.MerchantCurrencyTotal
import com.yourname.expensetracker.data.database.dao.MonthlyCurrencyTotal
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.TransactionTypeFilter
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.FailedConversion
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for multi-currency expense operations.
 * Handles currency conversion for queries and totals.
 *
 * == Rate-Basis Policy ==
 *
 * This repository uses two rate-basis strategies depending on the use case:
 *
 * **LATEST_AVAILABLE** — Used for current-state snapshots (e.g. current dashboard,
 * current-period totals, category breakdowns, merchant totals). These methods
 * call [CurrencyConverter.convertMultiple] which uses the most recent available
 * exchange rate. This is appropriate for "right now" views where the user is
 * looking at a live snapshot of their spending. Methods using this rate basis
 * are marked with `**LATEST-RATE**` in their KDoc.
 *
 * **AS_OF_TRANSACTION_DATE** — Planned for historical period reports where the
 * rate at the time of each transaction matters (e.g. year-over-year comparisons,
 * period-accurate P&L). These would use [CurrencyConverter.convertAsOf] with
 * each expense's date. A historical-rate aggregate API is planned for future
 * (see TODOs on individual methods).
 *
 * == Current State ==
 *
 * - **Totals** (total spending, monthly/daily/weekly aggregates): use latest-rate.
 * - **Normalized analytics** (via [AnalyticsCurrencyNormalizer]): already use
 *   as-of-transaction-date conversion through [CurrencyConverter.convertAsOf].
 *   See [AnalyticsRepository.getSpendingSummary] which pipelines normalized
 *   expenses (as-of-rate) for daily history while using latest-rate aggregates
 *   for the total display.
 *
 * - **Category/Merchant breakdowns**: use latest-rate. For historical accuracy,
 *   callers should use the normalized-analytics path instead.
 */
@Singleton
class MultiCurrencyRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val currencyConverter: CurrencyConverter,
    private val timeProvider: TimeProvider,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val normalizationEngine: com.yourname.expensetracker.domain.core.money.MoneyNormalizationEngine =
        com.yourname.expensetracker.domain.core.money.MoneyNormalizationEngine(currencyConverter)
) {
    sealed interface CurrencyRepositoryError {
        data class Dao(val message: String, val cause: Throwable? = null) : CurrencyRepositoryError
        data class Conversion(val message: String, val cause: Throwable? = null) : CurrencyRepositoryError
        data class Unknown(val message: String, val cause: Throwable? = null) : CurrencyRepositoryError
    }

    companion object {
        const val DEFAULT_HOME_CURRENCY = "EUR"
    }

    /**
     * **LATEST-RATE:** Get total expenses between dates, converted to home currency.
     *
     * Uses type-agnostic aggregate DAO helper [ExpenseDao.getAllSpentBetweenByCurrency]
     * so that the DB does the per-currency grouping with [ExpenseDao.EFFECTIVE_AMOUNT_SQL],
     * and only the per-currency totals are converted — no uncapped row scan needed.
     *
     * This method is intentionally **type-agnostic** (includes all transaction types),
     * preserving pre-A.10 semantics.  Transaction-type narrowing is deferred to A.10.
     *
     * Uses current exchange rates via [CurrencyConverter.convertMultiple].
     * For historical accuracy, use the per-row [CurrencyConverter.convertAsOf] approach
     * with each expense's date. A historical-rate aggregate API is planned for future
     * (TODO: getTotalExpensesInHomeCurrencyHistorical).
     */
    @Deprecated("Use getHomeCurrencyTotal() which returns MoneyAggregate with conversion quality metadata")
    suspend fun getTotalExpensesInHomeCurrency(
        startDate: Long,
        endDate: Long,
        homeCurrency: String = DEFAULT_HOME_CURRENCY
    ): Result<Double> = withContext(Dispatchers.IO) {
        runCatching {
            // A.9 Batch 5: type-agnostic aggregate SQL path replaces uncapped row scan.
            val currencyTotals = expenseDao.getAllSpentBetweenByCurrency(startDate, endDate)
            val amounts = currencyTotals.map { Pair(it.total, it.currency) }
            val aggregate = currencyConverter.convertMultiple(amounts, homeCurrency)
            if (aggregate.hasFailures) {
                throw MissingExchangeRateException(
                    buildMissingRateMessage(aggregate.failedConversions, homeCurrency),
                    aggregate.failedConversions
                )
            }
            aggregate.total
        }.fold(
            onSuccess = { Result.Success(it) },
            onFailure = {
                Timber.e(it, "getTotalExpensesInHomeCurrency failed")
                Result.Error(it, classifyErrorMessage(it))
            }
        )
    }

    /**
     * **LATEST-RATE:** Get expenses grouped by currency.
     *
     * Uses type-agnostic aggregate DAO helper [ExpenseDao.getAllSpentBetweenByCurrency]
     * which groups by `UPPER(currency)` and sums [ExpenseDao.EFFECTIVE_AMOUNT_SQL] —
     * no uncapped row scan needed.
     *
     * Intentionally **type-agnostic**, preserving pre-A.10 semantics.
     */
    @Deprecated("Returns raw Map<String, Double> without conversion quality. Use MoneyAggregate-based APIs instead.")
    suspend fun getExpensesByCurrency(
        startDate: Long,
        endDate: Long
    ): Result<Map<String, Double>> = withContext(Dispatchers.IO) {
        runCatching {
            // A.9 Batch 5: type-agnostic aggregate SQL path replaces uncapped row scan.
            val currencyTotals = expenseDao.getAllSpentBetweenByCurrency(startDate, endDate)
            currencyTotals.associate { it.currency to it.total }
        }.fold(
            onSuccess = { Result.Success(it) },
            onFailure = {
                Timber.e(it, "getExpensesByCurrency failed")
                Result.Error(it, classifyErrorMessage(it))
            }
        )
    }

    /**
     * Get expenses with converted amounts to home currency.
     * Converts [Expense.effectiveAmount] (ownership-adjusted) so that shared/not-mine rows
     * produce the correct converted value. The embedded [Expense] object is left unchanged
     * (raw fields untouched); only [ConvertedExpense.homeCurrencyAmount] reflects the effective share.
     *
     * This is the exhaustive row-complete path — it must remain a full row scan because
     * callers need per-row conversion results (rate, warning, original expense).
     */
    suspend fun getExpensesWithConversion(
        startDate: Long,
        endDate: Long,
        homeCurrency: String = DEFAULT_HOME_CURRENCY
    ): Result<List<ConvertedExpense>> = withContext(Dispatchers.IO) {
        runCatching {
            // A.9: use uncapped query to avoid silent LIMIT 2000 truncation.
            val expenses = expenseDao.getExpensesBetweenUncapped(startDate, endDate)
            val result = mutableListOf<ConvertedExpense>()

            for (expense in expenses) {
                // Convert effectiveAmount (ownership-adjusted) — the embedded Expense stays raw.
                val conversion = currencyConverter.convert(
                    expense.effectiveAmount,
                    expense.currency,
                    homeCurrency
                )

                result.add(
                    ConvertedExpense(
                        expense = expense,
                        homeCurrencyAmount = conversion?.convertedAmount,
                        conversionRate = conversion?.rateUsed,
                        homeCurrency = homeCurrency,
                        conversionWarning = if (conversion == null) {
                            "Missing exchange rate from ${expense.currency.uppercase()} to ${homeCurrency.uppercase()}"
                        } else {
                            null
                        }
                    )
                )
            }

            result
        }.fold(
            onSuccess = { Result.Success(it) },
            onFailure = {
                Timber.e(it, "getExpensesWithConversion failed")
                Result.Error(it, classifyErrorMessage(it))
            }
        )
    }

    /**
     * **LATEST-RATE:** Get spending totals by category in home currency.
     *
     * Uses type-agnostic grouped aggregate helper
     * [ExpenseDao.getAllCategoryTotalsBetweenByCurrency] which returns
     * (categoryId, currency, total) tuples — no uncapped row scan needed.
     * Null categoryId rows are preserved so that uncategorized expenses
     * appear in the result map.
     *
     * Intentionally **type-agnostic**, preserving pre-A.10 semantics.
     */
    @Deprecated("Use getHomeCurrencyCategoryTotals() which returns Map<Long?, MoneyAggregate>")
    suspend fun getCategoryTotalsInHomeCurrency(
        startDate: Long,
        endDate: Long,
        homeCurrency: String = DEFAULT_HOME_CURRENCY
    ): Result<Map<Long?, Double>> = withContext(Dispatchers.IO) {
        runCatching {
            // A.9 Batch 5: type-agnostic grouped aggregate path — no row scan.
            // Always use the grouped-by-currency query so that null categoryId rows
            // are preserved (the old single-currency fast-path used getCategoryTotalsBetween
            // which filtered `AND categoryId IS NOT NULL`, dropping uncategorized expenses).
            val grouped = expenseDao.getAllCategoryTotalsBetweenByCurrency(startDate, endDate)

            if (grouped.isEmpty()) {
                return@runCatching emptyMap<Long?, Double>()
            }

            val result = mutableMapOf<Long?, Double>()
            val failedConversions = mutableListOf<FailedConversion>()

            // Group by categoryId, then convert each (currency, total) pair.
            val byCategoryId = grouped.groupBy { it.categoryId }
            for ((categoryId, buckets) in byCategoryId) {
                val amounts = buckets.map { Pair(it.total, it.currency) }
                val aggregate = currencyConverter.convertMultiple(amounts, homeCurrency)
                failedConversions += aggregate.failedConversions
                val current = result[categoryId] ?: 0.0
                result[categoryId] = current + aggregate.total
            }

            if (failedConversions.isNotEmpty()) {
                throw MissingExchangeRateException(
                    buildMissingRateMessage(failedConversions, homeCurrency),
                    failedConversions
                )
            }

            result
        }.fold(
            onSuccess = { Result.Success(it) },
            onFailure = {
                Timber.e(it, "getCategoryTotalsInHomeCurrency failed")
                Result.Error(it, classifyErrorMessage(it))
            }
        )
    }

    /**
     * **LATEST-RATE:** Get merchant totals in home currency.
     *
     * Uses type-agnostic grouped aggregate helper
     * [ExpenseDao.getAllMerchantTotalsBetweenByCurrency] which returns
     * (merchant, currency, total) tuples — no uncapped row scan needed.
     * Rows with null merchantKey are grouped by raw merchant name,
     * preserving legacy inclusion semantics.
     * Results are sorted descending by total, preserving the original ordering contract.
     *
     * Intentionally **type-agnostic**, preserving pre-A.10 semantics.
     */
    @Deprecated("Use getHomeCurrencyMerchantTotals() which returns Map<String, MoneyAggregate>")
    suspend fun getMerchantTotalsInHomeCurrency(
        startDate: Long,
        endDate: Long,
        homeCurrency: String = DEFAULT_HOME_CURRENCY
    ): Result<Map<String, Double>> = withContext(Dispatchers.IO) {
        runCatching {
            // A.9 Batch 5: type-agnostic grouped aggregate path — no row scan.
            // Always use the grouped-by-currency query so that null-merchantKey
            // rows are included (the old single-currency fast-path used
            // getMerchantTotalsBetween which filtered `AND merchantKey IS NOT NULL`).
            val grouped = expenseDao.getAllMerchantTotalsBetweenByCurrency(startDate, endDate)

            if (grouped.isEmpty()) {
                return@runCatching emptyMap<String, Double>()
            }

            val result = mutableMapOf<String, Double>()
            val failedConversions = mutableListOf<FailedConversion>()

            // Group by merchant display name, then convert each (currency, total) pair.
            val byMerchant = grouped.groupBy { it.merchant }
            for ((merchant, buckets) in byMerchant) {
                val amounts = buckets.map { Pair(it.total, it.currency) }
                val aggregate = currencyConverter.convertMultiple(amounts, homeCurrency)
                failedConversions += aggregate.failedConversions
                val current = result[merchant] ?: 0.0
                result[merchant] = current + aggregate.total
            }

            if (failedConversions.isNotEmpty()) {
                throw MissingExchangeRateException(
                    buildMissingRateMessage(failedConversions, homeCurrency),
                    failedConversions
                )
            }

            result.toList().sortedByDescending { it.second }.toMap()
        }.fold(
            onSuccess = { Result.Success(it) },
            onFailure = {
                Timber.e(it, "getMerchantTotalsInHomeCurrency failed")
                Result.Error(it, classifyErrorMessage(it))
            }
        )
    }

    /**
     * **LATEST-RATE:** Get monthly totals in home currency.
     *
     * Uses type-agnostic grouped aggregate helper
     * [ExpenseDao.getAllMonthlyTotalsBetweenByCurrency] which returns
     * (monthKey, currency, total) tuples — no uncapped row scan needed.
     * Results are sorted ascending by month key.
     *
     * Intentionally **type-agnostic**, preserving pre-A.10 semantics.
     */
    @Deprecated("Use getHomeCurrencyMonthlyTotals() which returns List<MonthMoneyAggregate>")
    suspend fun getMonthlyTotalsInHomeCurrency(
        startDate: Long,
        endDate: Long,
        homeCurrency: String = DEFAULT_HOME_CURRENCY
    ): Result<List<MonthTotal>> = withContext(Dispatchers.IO) {
        runCatching {
            // A.9 Batch 5: type-agnostic grouped aggregate path — no row scan.
            val grouped = expenseDao.getAllMonthlyTotalsBetweenByCurrency(startDate, endDate)

            if (grouped.isEmpty()) {
                return@runCatching emptyList<MonthTotal>()
            }

            // Group by monthKey, then convert each (currency, total) pair.
            val byMonth = grouped.groupBy { it.monthKey }
            val result = mutableListOf<MonthTotal>()
            for ((monthKey, buckets) in byMonth.toSortedMap()) {
                val amounts = buckets.map { Pair(it.total, it.currency) }
                val aggregate = currencyConverter.convertMultiple(amounts, homeCurrency)
                result.add(
                    MonthTotal(
                        monthKey = monthKey,
                        total = aggregate.total,
                        homeCurrency = homeCurrency,
                        failedConversions = aggregate.failedConversions
                    )
                )
            }

            result
        }.fold(
            onSuccess = { Result.Success(it) },
            onFailure = {
                Timber.e(it, "getMonthlyTotalsInHomeCurrency failed")
                Result.Error(it, classifyErrorMessage(it))
            }
        )
    }

    /**
     * Update an expense's currency.
     */

    // ── Home-currency convenience methods ──────────────────────────────────
    // These read the home currency from CurrencySettingsRepository so callers
    // don't need to resolve it themselves.

    /**
     * Resolve the user's home currency using [HomeCurrencyResolution].
     * Returns the currency code string for Resolved and FirstRunDefault cases.
     * Throws [HomeCurrencyUnavailableException] for Failed case so callers
     * that return MoneyAggregate can catch and return unavailable state.
     *
     * CURR-70F-09: Uses typed resolution instead of raw homeCurrency().first().
     */
    private suspend fun resolveHomeCurrency(): String {
        return when (val resolution = currencySettingsRepository.resolveHomeCurrency()) {
            is HomeCurrencyResolution.Resolved -> resolution.currency.code
            is HomeCurrencyResolution.FirstRunDefault -> resolution.currency.code
            is HomeCurrencyResolution.Failed -> throw HomeCurrencyUnavailableException(resolution.reason)
        }
    }

    /**
     * Resolve home currency, returning MoneyAggregate.empty with UNAVAILABLE quality on failure.
     * Use this in aggregate-returning methods to avoid throwing.
     */
    private suspend fun resolveHomeCurrencyOrUnavailable(): Pair<CurrencyCode, Boolean> {
        return when (val resolution = currencySettingsRepository.resolveHomeCurrency()) {
            is HomeCurrencyResolution.Resolved -> CurrencyCode(resolution.currency.code) to false
            is HomeCurrencyResolution.FirstRunDefault -> CurrencyCode(resolution.currency.code) to false
            is HomeCurrencyResolution.Failed -> CurrencyCode("EUR") to true
        }
    }

    /**
     * **LATEST-RATE:** Get total expenses in home currency.
     *
     * Reads home currency from settings automatically.
     * Returns MoneyAggregate with per-currency source buckets and conversion failures.
     *
     * Uses current exchange rates via [CurrencyConverter.convertMultiple].
     * For historical accuracy, use [getHomeCurrencyPurchaseTotalHistorical] instead.
     */
    @Deprecated(
        "Type-agnostic latest-rate API. Use getHomeCurrencyPurchaseTotalHistorical() or getHomeCurrencyPurchaseTotal() with explicit rate semantics.",
        level = DeprecationLevel.ERROR
    )
    suspend fun getHomeCurrencyTotal(
        startDate: Long,
        endDate: Long
    ): MoneyAggregate {
        val homeCurrency = resolveHomeCurrency()
        val currencyTotals = expenseDao.getAllSpentBetweenByCurrency(startDate, endDate)
        return aggregateToMoneyAggregate(currencyTotals, homeCurrency)
    }

    /**
     * **LATEST-RATE:** Get category totals in home currency.
     *
     * Returns map of categoryId -> MoneyAggregate.
     *
     * Uses latest-rate conversion via [CurrencyConverter.convertMultiple].
     * Callers needing historical-rate accuracy should use the per-row
     * [CurrencyConverter.convertAsOf] approach instead.
     */
    suspend fun getHomeCurrencyCategoryTotals(
        startDate: Long,
        endDate: Long
    ): Map<Long?, MoneyAggregate> {
        val homeCurrency = resolveHomeCurrency()
        val grouped = expenseDao.getAllCategoryTotalsBetweenByCurrency(startDate, endDate)
        val byCategoryId = grouped.groupBy { it.categoryId }
        val result = mutableMapOf<Long?, MoneyAggregate>()
        for ((categoryId, buckets) in byCategoryId) {
            result[categoryId] = aggregateCurrencyTotalsToMoneyAggregate(buckets, homeCurrency)
        }
        return result
    }

    /**
     * **LATEST-RATE:** Get merchant totals in home currency.
     *
     * Returns map of merchant -> MoneyAggregate.
     *
     * Uses latest-rate conversion via [CurrencyConverter.convertMultiple].
     * Callers needing historical-rate accuracy should use the per-row
     * [CurrencyConverter.convertAsOf] approach instead.
     */
    suspend fun getHomeCurrencyMerchantTotals(
        startDate: Long,
        endDate: Long
    ): Map<String, MoneyAggregate> {
        val homeCurrency = resolveHomeCurrency()
        val grouped = expenseDao.getAllMerchantTotalsBetweenByCurrency(startDate, endDate)
        val byMerchant = grouped.groupBy { it.merchant }
        val result = mutableMapOf<String, MoneyAggregate>()
        for ((merchant, buckets) in byMerchant) {
            result[merchant] = aggregateCurrencyTotalsToMoneyAggregate(buckets, homeCurrency)
        }
        return result
    }

    /**
     * **LATEST-RATE:** Get monthly totals in home currency.
     *
     * Returns list of MonthMoneyAggregate sorted by monthKey ascending.
     *
     * Uses latest-rate conversion via [CurrencyConverter.convertMultiple].
     * Callers needing historical-rate accuracy should use the per-row
     * [CurrencyConverter.convertAsOf] approach instead.
     */
    suspend fun getHomeCurrencyMonthlyTotals(
        startDate: Long,
        endDate: Long
    ): List<MonthMoneyAggregate> {
        val homeCurrency = resolveHomeCurrency()
        val grouped = expenseDao.getAllMonthlyTotalsBetweenByCurrency(startDate, endDate)
        val byMonth = grouped.groupBy { it.monthKey }
        val result = mutableListOf<MonthMoneyAggregate>()
        for ((monthKey, buckets) in byMonth.toSortedMap()) {
            val aggregate = aggregateCurrencyTotalsToMoneyAggregate(buckets, homeCurrency)
            result.add(MonthMoneyAggregate(monthKey, aggregate))
        }
        return result
    }

    /**
     * Get weekly totals in home currency.
     * Groups expenses by ISO week using [TimePeriodUtils.getStartOfWeek].
     * Uses [MoneyAggregateBuilder.fromBuckets] for safe multi-currency conversion.
     * Returns list of [PeriodMoneyAggregate] sorted by week ascending.
     */
    @Deprecated(
        "Uses latest-rate conversion. Use getWeeklyAggregatesHistorical() for historical accuracy.",
        level = DeprecationLevel.WARNING
    )
    suspend fun getHomeCurrencyWeeklyTotals(
        startDate: Long,
        endDate: Long
    ): List<PeriodMoneyAggregate> {
        val homeCurrency = resolveHomeCurrency()
        val expenses = expenseDao.getExpensesBetweenUncapped(startDate, endDate)
        if (expenses.isEmpty()) return emptyList()

        val byWeek = expenses.groupBy { TimePeriodUtils.getStartOfWeek(it.date) }
        val result = mutableListOf<PeriodMoneyAggregate>()
        for ((weekStart, group) in byWeek.toSortedMap()) {
            val buckets = group.map { Pair(it.effectiveAmount, it.currency) }
            val aggregate = MoneyAggregateBuilder.fromBuckets(buckets, homeCurrency, currencyConverter, transactionCounts = List(group.size) { 1 })
            result.add(PeriodMoneyAggregate(periodKey = getWeekKey(weekStart), aggregate = aggregate))
        }
        return result
    }

    /**
     * Get daily totals in home currency.
     * Groups expenses by calendar day using [TimePeriodUtils.getStartOfDay].
     * Uses [MoneyAggregateBuilder.fromBuckets] for safe multi-currency conversion.
     * Returns list of [PeriodMoneyAggregate] sorted by day ascending.
     */
    @Deprecated(
        "Uses latest-rate conversion. Use getDailyAggregatesHistorical() for historical accuracy.",
        level = DeprecationLevel.WARNING
    )
    suspend fun getHomeCurrencyDailyTotals(
        startDate: Long,
        endDate: Long
    ): List<PeriodMoneyAggregate> {
        val homeCurrency = resolveHomeCurrency()
        val expenses = expenseDao.getExpensesBetweenUncapped(startDate, endDate)
        if (expenses.isEmpty()) return emptyList()

        val byDay = expenses.groupBy { TimePeriodUtils.getStartOfDay(it.date) }
        val result = mutableListOf<PeriodMoneyAggregate>()
        for ((dayStart, group) in byDay.toSortedMap()) {
            val buckets = group.map { Pair(it.effectiveAmount, it.currency) }
            val aggregate = MoneyAggregateBuilder.fromBuckets(buckets, homeCurrency, currencyConverter, transactionCounts = List(group.size) { 1 })
            result.add(PeriodMoneyAggregate(periodKey = getDayKey(dayStart), aggregate = aggregate))
        }
        return result
    }

    // ── PURCHASE-only variants ─────────────────────────────────────────────

    /**
     * **HISTORICAL-RATE:** Get total PURCHASE spending in home currency using
     * per-expense transaction-date conversion via [MoneyNormalizationEngine].
     *
     * CURR-70F-08: Replaced midpoint+latest-fallback approach with proper
     * per-expense TRANSACTION_DATE conversion. Never falls back to latest rate.
     */
    suspend fun getHomeCurrencyPurchaseTotalHistorical(
        startDate: Long,
        endDate: Long
    ): MoneyAggregate {
        val homeCurrency = try {
            CurrencyCode(resolveHomeCurrency())
        } catch (e: HomeCurrencyUnavailableException) {
            return MoneyAggregate.empty(CurrencyCode(""), RateBasis.TRANSACTION_DATE).copy(
                conversionQuality = com.yourname.expensetracker.domain.core.money.ConversionQuality.UNAVAILABLE,
                warningMessage = "Home currency unavailable: ${e.reason}"
            )
        }
        val expenses = expenseDao.getExpensesBetweenUncapped(startDate, endDate)
        if (expenses.isEmpty()) return MoneyAggregate.empty(homeCurrency, RateBasis.TRANSACTION_DATE)

        return normalizationEngine.aggregateExpenses(
            expenses = expenses,
            homeCurrency = homeCurrency,
            rateBasis = RateBasis.TRANSACTION_DATE,
            transactionTypeFilter = TransactionTypeFilter.PURCHASE_ONLY
        )
    }

    // ── Explicit historical API family (CURR-70F-08) ───────────────────────

    /**
     * **HISTORICAL-RATE:** Get category totals using per-expense transaction-date conversion.
     * Returns map of categoryId -> MoneyAggregate with TRANSACTION_DATE basis.
     */
    suspend fun getCategoryAggregatesHistorical(
        startDate: Long,
        endDate: Long,
        transactionTypeFilter: TransactionTypeFilter = TransactionTypeFilter.PURCHASE_ONLY
    ): Map<Long?, MoneyAggregate> {
        val homeCurrency = CurrencyCode(resolveHomeCurrency())
        val expenses = expenseDao.getExpensesBetweenUncapped(startDate, endDate)
        if (expenses.isEmpty()) return emptyMap()

        val byCategory = expenses.groupBy { it.categoryId }
        return byCategory.mapValues { (_, group) ->
            normalizationEngine.aggregateExpenses(group, homeCurrency, RateBasis.TRANSACTION_DATE, transactionTypeFilter)
        }
    }

    /**
     * **HISTORICAL-RATE:** Get daily totals using per-expense transaction-date conversion.
     * Returns list of [PeriodMoneyAggregate] sorted by day ascending.
     */
    suspend fun getDailyAggregatesHistorical(
        startDate: Long,
        endDate: Long,
        transactionTypeFilter: TransactionTypeFilter = TransactionTypeFilter.PURCHASE_ONLY
    ): List<PeriodMoneyAggregate> {
        val homeCurrency = CurrencyCode(resolveHomeCurrency())
        val expenses = expenseDao.getExpensesBetweenUncapped(startDate, endDate)
        if (expenses.isEmpty()) return emptyList()

        val byDay = expenses.groupBy { TimePeriodUtils.getStartOfDay(it.date) }
        return byDay.toSortedMap().map { (dayStart, group) ->
            PeriodMoneyAggregate(
                periodKey = getDayKey(dayStart),
                aggregate = normalizationEngine.aggregateExpenses(group, homeCurrency, RateBasis.TRANSACTION_DATE, transactionTypeFilter)
            )
        }
    }

    /**
     * **HISTORICAL-RATE:** Get weekly totals using per-expense transaction-date conversion.
     * Returns list of [PeriodMoneyAggregate] sorted by week ascending.
     */
    suspend fun getWeeklyAggregatesHistorical(
        startDate: Long,
        endDate: Long,
        transactionTypeFilter: TransactionTypeFilter = TransactionTypeFilter.PURCHASE_ONLY
    ): List<PeriodMoneyAggregate> {
        val homeCurrency = CurrencyCode(resolveHomeCurrency())
        val expenses = expenseDao.getExpensesBetweenUncapped(startDate, endDate)
        if (expenses.isEmpty()) return emptyList()

        val byWeek = expenses.groupBy { TimePeriodUtils.getStartOfWeek(it.date) }
        return byWeek.toSortedMap().map { (weekStart, group) ->
            PeriodMoneyAggregate(
                periodKey = getWeekKey(weekStart),
                aggregate = normalizationEngine.aggregateExpenses(group, homeCurrency, RateBasis.TRANSACTION_DATE, transactionTypeFilter)
            )
        }
    }

    /**
     * **HISTORICAL-RATE:** Get monthly totals using per-expense transaction-date conversion.
     * Returns list of [MonthMoneyAggregate] sorted by month ascending.
     */
    suspend fun getMonthlyAggregatesHistorical(
        startDate: Long,
        endDate: Long,
        transactionTypeFilter: TransactionTypeFilter = TransactionTypeFilter.PURCHASE_ONLY
    ): List<MonthMoneyAggregate> {
        val homeCurrency = CurrencyCode(resolveHomeCurrency())
        val expenses = expenseDao.getExpensesBetweenUncapped(startDate, endDate)
        if (expenses.isEmpty()) return emptyList()

        val byMonth = expenses.groupBy { getMonthKey(it.date) }
        return byMonth.toSortedMap().map { (monthKey, group) ->
            MonthMoneyAggregate(
                monthKey = monthKey,
                aggregate = normalizationEngine.aggregateExpenses(group, homeCurrency, RateBasis.TRANSACTION_DATE, transactionTypeFilter)
            )
        }
    }

    /**
     * **LATEST-RATE:** Get total PURCHASE spending in home currency.
     *
     * Uses the PURCHASE-filtered DAO variant.
     *
     * Uses latest-rate conversion via [CurrencyConverter.convertMultiple].
     * Callers needing historical-rate accuracy should use
     * [getHomeCurrencyPurchaseTotalHistorical] instead.
     */
    suspend fun getHomeCurrencyPurchaseTotal(
        startDate: Long,
        endDate: Long
    ): MoneyAggregate {
        val homeCurrency = resolveHomeCurrency()
        val currencyTotals = expenseDao.getTotalSpentBetweenByCurrency(startDate, endDate)
        return aggregateToMoneyAggregate(currencyTotals, homeCurrency)
    }

    /**
     * **LATEST-RATE:** Get the DEPOSIT total in home currency for the given date range.
     *
     * Uses the DEPOSIT-filtered DAO variant for currency-aware aggregation.
     */
    suspend fun getHomeCurrencyDepositTotal(
        startDate: Long,
        endDate: Long
    ): MoneyAggregate {
        val homeCurrency = resolveHomeCurrency()
        val currencyTotals = expenseDao.getDepositTotalsBetweenByCurrency(startDate, endDate)
        return aggregateToMoneyAggregate(currencyTotals, homeCurrency)
    }

    /**
     * Get PURCHASE category totals in home currency.
     * Uses the PURCHASE-filtered DAO variant.
     */
    suspend fun getHomeCurrencyPurchaseCategoryTotals(
        startDate: Long,
        endDate: Long
    ): Map<Long?, MoneyAggregate> {
        val homeCurrency = resolveHomeCurrency()
        val grouped = expenseDao.getCategoryTotalsBetweenByCurrency(startDate, endDate)
        val byCategoryId = grouped.groupBy { it.categoryId }
        val result = mutableMapOf<Long?, MoneyAggregate>()
        for ((categoryId, buckets) in byCategoryId) {
            result[categoryId] = aggregateCurrencyTotalsToMoneyAggregate(buckets, homeCurrency)
        }
        return result
    }

    /**
     * P5-CURRENT-006: **LATEST-RATE:** Get PURCHASE monthly totals in home currency.
     *
     * Uses the PURCHASE-filtered DAO variant [ExpenseDao.getMonthlyTotalsBetweenByCurrency]
     * which filters by transactionType = PURCHASE. Returns list of MonthMoneyAggregate
     * sorted by monthKey ascending.
     */
    suspend fun getHomeCurrencyPurchaseMonthlyTotals(
        startDate: Long,
        endDate: Long
    ): List<MonthMoneyAggregate> {
        val homeCurrency = resolveHomeCurrency()
        val grouped = expenseDao.getMonthlyTotalsBetweenByCurrency(startDate, endDate)
        val byMonth = grouped.groupBy { it.monthKey }
        val result = mutableListOf<MonthMoneyAggregate>()
        for ((monthKey, buckets) in byMonth.toSortedMap()) {
            val aggregate = aggregateCurrencyTotalsToMoneyAggregate(buckets, homeCurrency)
            result.add(MonthMoneyAggregate(monthKey, aggregate))
        }
        return result
    }

    // ── Internal aggregation helpers ───────────────────────────────────────

    /**
     * Convert a list of CurrencyTotal (per-currency totals) into a MoneyAggregate.
     * Uses [MoneyAggregateBuilder.fromBuckets] for consistent warning messages
     * that report failed transaction counts, not just bucket counts.
     */
    private suspend fun aggregateToMoneyAggregate(
        currencyTotals: List<CurrencyTotal>,
        homeCurrency: String
    ): MoneyAggregate {
        if (currencyTotals.isEmpty()) {
            return MoneyAggregate.empty(CurrencyCode(homeCurrency))
        }
        return MoneyAggregateBuilder.fromBuckets(
            buckets = currencyTotals.map { Pair(it.total, it.currency) },
            homeCurrency = homeCurrency,
            converter = currencyConverter,
            transactionCounts = currencyTotals.map { it.txCount }
        )
    }

    /**
     * Convert a list of same-dimension currency totals into a MoneyAggregate.
     * Used for category/merchant/month grouping where we have sub-buckets per key.
     * Uses [MoneyAggregateBuilder.fromBuckets] for consistent warning messages
     * that report failed transaction counts, not just bucket counts.
     */
    private suspend fun aggregateCurrencyTotalsToMoneyAggregate(
        buckets: List<*>,
        homeCurrency: String
    ): MoneyAggregate {
        if (buckets.isEmpty()) {
            return MoneyAggregate.empty(CurrencyCode(homeCurrency))
        }
        val amounts = mutableListOf<Pair<Double, String>>()
        val counts = mutableListOf<Int>()
        for (bucket in buckets) {
            when (bucket) {
                is CategoryCurrencyTotal -> {
                    amounts.add(Pair(bucket.total, bucket.currency))
                    counts.add(bucket.txCount)
                }
                is MerchantCurrencyTotal -> {
                    amounts.add(Pair(bucket.total, bucket.currency))
                    counts.add(bucket.txCount)
                }
                is MonthlyCurrencyTotal -> {
                    amounts.add(Pair(bucket.total, bucket.currency))
                    counts.add(bucket.txCount)
                }
                else -> {
                    Timber.w("Unexpected bucket type in aggregate: ${bucket?.javaClass?.name}")
                    return MoneyAggregate.empty(CurrencyCode(homeCurrency))
                }
            }
        }
        return MoneyAggregateBuilder.fromBuckets(
            buckets = amounts,
            homeCurrency = homeCurrency,
            converter = currencyConverter,
            transactionCounts = counts
        )
    }
    suspend fun updateExpenseCurrency(
        expenseId: Long,
        newCurrency: String,
        convertedAmount: Double? = null
    ) {
        // This would need a new DAO method
        // For now, this is a placeholder
        // expenseDao.updateCurrency(expenseId, newCurrency, convertedAmount)
    }

    /**
     * Check if rates need updating (older than 24 hours).
     */
    suspend fun shouldUpdateRates(): Boolean {
        val lastUpdate = currencyConverter.getLastUpdateTime() ?: return true
        // DAY_IN_MILLIS for rate staleness check — acceptable TTL usage (not calendar math)
        val twentyFourHours = TimePeriodUtils.DAY_IN_MILLIS
        return (timeProvider.now() - lastUpdate) > twentyFourHours
    }

    /**
     * Resolve the conversion rate from [fromCurrency] to [toCurrency].
     * Returns 1.0 for same-currency pairs. Returns null if no rate is available,
     * which the caller should treat as a missing-rate error.
     */
    private suspend fun resolveConversionRate(fromCurrency: String, toCurrency: String): Double? {
        if (fromCurrency.uppercase() == toCurrency.uppercase()) return 1.0
        val result = currencyConverter.convert(1.0, fromCurrency, toCurrency)
        return result?.rateUsed
    }

    /**
     * Build a [MissingExchangeRateException] for a single missing currency pair.
     */
    private fun missingRateException(fromCurrency: String, toCurrency: String): MissingExchangeRateException {
        val failed = FailedConversion(
            originalAmount = 0.0,
            originalCurrency = fromCurrency,
            targetCurrency = toCurrency,
            reason = "Missing exchange rate from ${fromCurrency.uppercase()} to ${toCurrency.uppercase()}"
        )
        return MissingExchangeRateException(
            buildMissingRateMessage(listOf(failed), toCurrency),
            listOf(failed)
        )
    }

    private fun getMonthKey(timestamp: Long): String {
        val year = TimePeriodUtils.getYear(timestamp)
        val month = TimePeriodUtils.getMonth(timestamp) + 1
        return "$year-${month.toString().padStart(2, '0')}"
    }

    /**
     * Format a week key from a timestamp using ISO week-based year.
     * Always use [TimePeriodUtils.getWeekBasedYear] with [TimePeriodUtils.getWeekOfYear]
     * to avoid year-boundary mismatches (e.g. 2021-01-01 → "2020-W53").
     */
    private fun getWeekKey(timestamp: Long): String {
        val year = TimePeriodUtils.getWeekBasedYear(timestamp)
        val week = TimePeriodUtils.getWeekOfYear(timestamp)
        return "$year-W${week.toString().padStart(2, '0')}"
    }

    /**
     * Format a day key from a timestamp as "yyyy-MM-dd".
     */
    private fun getDayKey(timestamp: Long): String {
        val year = TimePeriodUtils.getYear(timestamp)
        val month = TimePeriodUtils.getMonth(timestamp) + 1
        val day = TimePeriodUtils.getDayOfMonth(timestamp)
        return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    private fun classifyErrorMessage(throwable: Throwable): String {
        return when {
            throwable is MissingExchangeRateException ->
                throwable.message ?: "Missing exchange rates for one or more conversions"
            throwable is android.database.SQLException ->
                CurrencyRepositoryError.Dao("Database operation failed", throwable).message
            throwable is IllegalArgumentException ->
                CurrencyRepositoryError.Conversion("Currency conversion input invalid", throwable).message
            else ->
                CurrencyRepositoryError.Unknown("Unexpected multi-currency error", throwable).message
        }
    }

    private fun buildMissingRateMessage(
        failedConversions: List<FailedConversion>,
        homeCurrency: String
    ): String {
        val missingPairs = failedConversions
            .map { "${it.originalCurrency.uppercase()}→${homeCurrency.uppercase()}" }
            .distinct()
            .sorted()
        return "Missing exchange rates: ${missingPairs.joinToString(", ")}".trim()
    }
}

private class MissingExchangeRateException(
    override val message: String,
    val failedConversions: List<FailedConversion>
) : IllegalStateException(message)

/**
 * CURR-70F-09: Thrown when home currency settings cannot be read.
 * Callers should catch this and return unavailable/partial MoneyAggregate.
 */
class HomeCurrencyUnavailableException(
    val reason: String
) : IllegalStateException("Home currency unavailable: $reason")

/**
 * Expense with converted amount information.
 */
data class ConvertedExpense(
    val expense: Expense,
    val homeCurrencyAmount: Double?,
    val conversionRate: Double?,
    val homeCurrency: String,
    val conversionWarning: String? = null
)

/**
 * Monthly total with currency information.
 */
data class MonthTotal(
    val monthKey: String,
    val total: Double,
    val homeCurrency: String,
    val failedConversions: List<FailedConversion> = emptyList()
)

data class MonthMoneyAggregate(
    val monthKey: String,
    val aggregate: MoneyAggregate
)

/**
 * Aggregate for a time period (week or day) with home-currency conversion.
 * Used by [MultiCurrencyRepository.getHomeCurrencyWeeklyTotals] and
 * [MultiCurrencyRepository.getHomeCurrencyDailyTotals].
 *
 * @param periodKey Human-readable key (e.g. "2026-W19" for week, "2026-05-09" for day).
 * @param aggregate The converted money aggregate for this period.
 */
data class PeriodMoneyAggregate(
    val periodKey: String,
    val aggregate: MoneyAggregate
)
