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
     * Converts [Expense.effectiveAmount] (ownership-adjusted) rather than the raw posted amount
     * so that shared and "not-mine" rows are not overstated.
     */
    suspend fun getTotalExpensesInHomeCurrency(
        startDate: Long,
        endDate: Long,
        homeCurrency: String = DEFAULT_HOME_CURRENCY
    ): Result<Double> = withContext(Dispatchers.IO) {
        runCatching {
            val expenses = expenseDao.getExpensesBetween(startDate, endDate)
            // Use effectiveAmount (ownership-adjusted) — not raw amount — for conversion input.
            val amounts = expenses.map { Pair(it.effectiveAmount, it.currency) }
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
     * Accumulates [Expense.effectiveAmount] (ownership-adjusted) per currency bucket so that
     * shared and "not-mine" rows are not overstated in the per-currency breakdown.
     */
    suspend fun getExpensesByCurrency(
        startDate: Long,
        endDate: Long
    ): Result<Map<String, Double>> = withContext(Dispatchers.IO) {
        runCatching {
            val expenses = expenseDao.getExpensesBetween(startDate, endDate)
            val result = mutableMapOf<String, Double>()

            for (expense in expenses) {
                val current = result[expense.currency] ?: 0.0
                // Use effectiveAmount (ownership-adjusted) — not raw amount.
                result[expense.currency] = current + expense.effectiveAmount
            }

            result
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
     */
    suspend fun getExpensesWithConversion(
        startDate: Long,
        endDate: Long,
        homeCurrency: String = DEFAULT_HOME_CURRENCY
    ): Result<List<ConvertedExpense>> = withContext(Dispatchers.IO) {
        runCatching {
            val expenses = expenseDao.getExpensesBetween(startDate, endDate)
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
     * Converts [Expense.effectiveAmount] (ownership-adjusted) per expense so that shared and
     * "not-mine" rows are not overstated in the category breakdown.
     */
    suspend fun getCategoryTotalsInHomeCurrency(
        startDate: Long,
        endDate: Long,
        homeCurrency: String = DEFAULT_HOME_CURRENCY
    ): Result<Map<Long?, Double>> = withContext(Dispatchers.IO) {
        runCatching {
            val expenses = expenseDao.getExpensesBetween(startDate, endDate)
            val result = mutableMapOf<Long?, Double>()
            val failedConversions = mutableListOf<FailedConversion>()

            for (expense in expenses) {
                // Convert effectiveAmount (ownership-adjusted) — not raw amount.
                val conversion = currencyConverter.convert(
                    expense.effectiveAmount,
                    expense.currency,
                    homeCurrency
                )

                if (conversion == null) {
                    failedConversions += FailedConversion(
                        originalAmount = expense.effectiveAmount,
                        originalCurrency = expense.currency,
                        targetCurrency = homeCurrency,
                        reason = "Missing exchange rate from ${expense.currency.uppercase()} to ${homeCurrency.uppercase()}"
                    )
                    continue
                }

                val convertedAmount = conversion.convertedAmount
                val current = result[expense.categoryId] ?: 0.0
                result[expense.categoryId] = current + convertedAmount
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
     * Converts [Expense.effectiveAmount] (ownership-adjusted) per expense so that shared and
     * "not-mine" rows are not overstated in the merchant ranking.
     */
    suspend fun getMerchantTotalsInHomeCurrency(
        startDate: Long,
        endDate: Long,
        homeCurrency: String = DEFAULT_HOME_CURRENCY
    ): Result<Map<String, Double>> = withContext(Dispatchers.IO) {
        runCatching {
            val expenses = expenseDao.getExpensesBetween(startDate, endDate)
            val result = mutableMapOf<String, Double>()
            val failedConversions = mutableListOf<FailedConversion>()

            for (expense in expenses) {
                // Convert effectiveAmount (ownership-adjusted) — not raw amount.
                val conversion = currencyConverter.convert(
                    expense.effectiveAmount,
                    expense.currency,
                    homeCurrency
                )

                if (conversion == null) {
                    failedConversions += FailedConversion(
                        originalAmount = expense.effectiveAmount,
                        originalCurrency = expense.currency,
                        targetCurrency = homeCurrency,
                        reason = "Missing exchange rate from ${expense.currency.uppercase()} to ${homeCurrency.uppercase()}"
                    )
                    continue
                }

                val convertedAmount = conversion.convertedAmount
                val merchant = expense.merchant
                val current = result[merchant] ?: 0.0
                result[merchant] = current + convertedAmount
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
     * Accumulates [Expense.effectiveAmount] (ownership-adjusted) per month bucket before
     * converting, so that shared and "not-mine" rows are not overstated in monthly totals.
     */
    suspend fun getMonthlyTotalsInHomeCurrency(
        startDate: Long,
        endDate: Long,
        homeCurrency: String = DEFAULT_HOME_CURRENCY
    ): Result<List<MonthTotal>> = withContext(Dispatchers.IO) {
        runCatching {
            val expenses = expenseDao.getExpensesBetween(startDate, endDate)
            val monthlyMap = mutableMapOf<String, MutableList<Pair<Double, String>>>()

            for (expense in expenses) {
                val monthKey = getMonthKey(expense.date)
                val list = monthlyMap.getOrPut(monthKey) { mutableListOf() }
                // Use effectiveAmount (ownership-adjusted) — not raw amount — for conversion.
                list.add(Pair(expense.effectiveAmount, expense.currency))
            }

            val result = mutableListOf<MonthTotal>()
            for ((monthKey, amounts) in monthlyMap.toSortedMap()) {
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
