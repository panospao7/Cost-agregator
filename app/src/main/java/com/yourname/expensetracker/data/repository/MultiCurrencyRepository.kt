package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
    companion object {
        const val DEFAULT_HOME_CURRENCY = "EUR"
    }

    /**
     * Get total expenses between dates, converted to home currency.
     */
    suspend fun getTotalExpensesInHomeCurrency(
        startDate: Long,
        endDate: Long,
        homeCurrency: String = DEFAULT_HOME_CURRENCY
    ): Double = withContext(Dispatchers.IO) {
        val expenses = expenseDao.getExpensesBetween(startDate, endDate)
        val amounts = expenses.map { Pair(it.amount, it.currency) }
        currencyConverter.convertMultiple(amounts, homeCurrency)
    }

    /**
     * Get expenses grouped by currency.
     */
    suspend fun getExpensesByCurrency(
        startDate: Long,
        endDate: Long
    ): Map<String, Double> = withContext(Dispatchers.IO) {
        val expenses = expenseDao.getExpensesBetween(startDate, endDate)
        val result = mutableMapOf<String, Double>()
        
        for (expense in expenses) {
            val current = result[expense.currency] ?: 0.0
            result[expense.currency] = current + expense.amount
        }
        
        result
    }

    /**
     * Get expenses with converted amounts to home currency.
     */
    suspend fun getExpensesWithConversion(
        startDate: Long,
        endDate: Long,
        homeCurrency: String = DEFAULT_HOME_CURRENCY
    ): List<ConvertedExpense> = withContext(Dispatchers.IO) {
        val expenses = expenseDao.getExpensesBetween(startDate, endDate)
        val result = mutableListOf<ConvertedExpense>()
        
        for (expense in expenses) {
            val conversion = currencyConverter.convert(
                expense.amount,
                expense.currency,
                homeCurrency
            )
            
            result.add(
                ConvertedExpense(
                    expense = expense,
                    homeCurrencyAmount = conversion?.convertedAmount ?: expense.amount,
                    conversionRate = conversion?.rateUsed ?: 1.0,
                    homeCurrency = homeCurrency
                )
            )
        }
        
        result
    }

    /**
     * Get spending totals by category in home currency.
     */
    suspend fun getCategoryTotalsInHomeCurrency(
        startDate: Long,
        endDate: Long,
        homeCurrency: String = DEFAULT_HOME_CURRENCY
    ): Map<Long?, Double> = withContext(Dispatchers.IO) {
        val expenses = expenseDao.getExpensesBetween(startDate, endDate)
        val result = mutableMapOf<Long?, Double>()
        
        for (expense in expenses) {
            val conversion = currencyConverter.convert(
                expense.amount,
                expense.currency,
                homeCurrency
            )
            
            val convertedAmount = conversion?.convertedAmount ?: expense.amount
            val current = result[expense.categoryId] ?: 0.0
            result[expense.categoryId] = current + convertedAmount
        }
        
        result
    }

    /**
     * Get merchant totals in home currency.
     */
    suspend fun getMerchantTotalsInHomeCurrency(
        startDate: Long,
        endDate: Long,
        homeCurrency: String = DEFAULT_HOME_CURRENCY
    ): Map<String, Double> = withContext(Dispatchers.IO) {
        val expenses = expenseDao.getExpensesBetween(startDate, endDate)
        val result = mutableMapOf<String, Double>()
        
        for (expense in expenses) {
            val conversion = currencyConverter.convert(
                expense.amount,
                expense.currency,
                homeCurrency
            )
            
            val convertedAmount = conversion?.convertedAmount ?: expense.amount
            val merchant = expense.merchant
            val current = result[merchant] ?: 0.0
            result[merchant] = current + convertedAmount
        }
        
        result.toList().sortedByDescending { it.second }.toMap()
    }

    /**
     * Get monthly totals in home currency.
     */
    suspend fun getMonthlyTotalsInHomeCurrency(
        startDate: Long,
        endDate: Long,
        homeCurrency: String = DEFAULT_HOME_CURRENCY
    ): List<MonthTotal> = withContext(Dispatchers.IO) {
        val expenses = expenseDao.getExpensesBetween(startDate, endDate)
        val monthlyMap = mutableMapOf<String, MutableList<Pair<Double, String>>>()
        
        for (expense in expenses) {
            val monthKey = getMonthKey(expense.date)
            val list = monthlyMap.getOrPut(monthKey) { mutableListOf() }
            list.add(Pair(expense.amount, expense.currency))
        }
        
        val result = mutableListOf<MonthTotal>()
        for ((monthKey, amounts) in monthlyMap.toSortedMap()) {
            val total = currencyConverter.convertMultiple(amounts, homeCurrency)
            result.add(
                MonthTotal(
                    monthKey = monthKey,
                    total = total,
                    homeCurrency = homeCurrency
                )
            )
        }
        
        result
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
}

/**
 * Expense with converted amount information.
 */
data class ConvertedExpense(
    val expense: Expense,
    val homeCurrencyAmount: Double,
    val conversionRate: Double,
    val homeCurrency: String
)

/**
 * Monthly total with currency information.
 */
data class MonthTotal(
    val monthKey: String,
    val total: Double,
    val homeCurrency: String
)
