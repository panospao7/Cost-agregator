package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.FailedConversion
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for multi-currency expense operations.
 * Handles currency conversion for queries and totals.
 */
@Singleton
class MultiCurrencyRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val currencyConverter: CurrencyConverter,
    private val timeProvider: TimeProvider
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
     * Get total expenses between dates, converted to home currency.
     * Uses type-agnostic aggregate DAO helper [ExpenseDao.getAllSpentBetweenByCurrency]
     * so that the DB does the per-currency grouping with [ExpenseDao.EFFECTIVE_AMOUNT_SQL],
     * and only the per-currency totals are converted — no uncapped row scan needed.
     *
     * This method is intentionally **type-agnostic** (includes all transaction types),
     * preserving pre-A.10 semantics.  Transaction-type narrowing is deferred to A.10.
     */
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
     * Get expenses grouped by currency.
     * Uses type-agnostic aggregate DAO helper [ExpenseDao.getAllSpentBetweenByCurrency]
     * which groups by `UPPER(currency)` and sums [ExpenseDao.EFFECTIVE_AMOUNT_SQL] —
     * no uncapped row scan needed.
     *
     * Intentionally **type-agnostic**, preserving pre-A.10 semantics.
     */
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
     * Get spending totals by category in home currency.
     * Uses type-agnostic grouped aggregate helper
     * [ExpenseDao.getAllCategoryTotalsBetweenByCurrency] which returns
     * (categoryId, currency, total) tuples — no uncapped row scan needed.
     * Null categoryId rows are preserved so that uncategorized expenses
     * appear in the result map.
     *
     * Intentionally **type-agnostic**, preserving pre-A.10 semantics.
     */
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
     * Get merchant totals in home currency.
     * Uses type-agnostic grouped aggregate helper
     * [ExpenseDao.getAllMerchantTotalsBetweenByCurrency] which returns
     * (merchant, currency, total) tuples — no uncapped row scan needed.
     * Rows with null merchantKey are grouped by raw merchant name,
     * preserving legacy inclusion semantics.
     * Results are sorted descending by total, preserving the original ordering contract.
     *
     * Intentionally **type-agnostic**, preserving pre-A.10 semantics.
     */
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
     * Get monthly totals in home currency.
     * Uses type-agnostic grouped aggregate helper
     * [ExpenseDao.getAllMonthlyTotalsBetweenByCurrency] which returns
     * (monthKey, currency, total) tuples — no uncapped row scan needed.
     * Results are sorted ascending by month key.
     *
     * Intentionally **type-agnostic**, preserving pre-A.10 semantics.
     */
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
