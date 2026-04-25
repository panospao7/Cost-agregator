package com.yourname.expensetracker.domain.income

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tracks and analyzes recurring income patterns.
 */
@Singleton
class RecurringIncomeTracker @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val expenseRepository: ExpenseRepository,
    private val timeProvider: TimeProvider
) {
    companion object {
        // Confidence thresholds are expressed in day-scale standard deviation.
        private const val HIGH_REGULARITY_STDDEV_DAYS = 2.0
        private const val MEDIUM_REGULARITY_STDDEV_DAYS = 5.0
        private const val ACCEPTABLE_REGULARITY_STDDEV_DAYS = 7.0
    }
    
    /**
     * Detect recurring income patterns from deposit transactions.
     */
    suspend fun detectRecurringIncome(): List<RecurringIncome> = withContext(Dispatchers.IO) {
        val now = timeProvider.now()
        val threeMonthsAgo = TimePeriodUtils.addMonths(now, -3)
        val deposits = expenseDao.getExpensesByTypeBetweenUncapped(
            threeMonthsAgo,
            now,
            DomainTransactionType.DEPOSIT.name
        )
        
        // Group by normalized merchant/payer key
        val merchantGroups = deposits
            .groupBy { MerchantKeyGenerator.generate(it.merchant).trim() }
            .filterKeys { it.isNotBlank() }
        
        val recurring = mutableListOf<RecurringIncome>()
        
        for ((_, transactions) in merchantGroups) {
            if (transactions.size >= 2) { // At least 2 occurrences
                val sorted = transactions.sortedBy { it.date }
                val intervalDays = mutableListOf<Double>()
                val merchant = sorted.groupBy { it.merchant }
                    .maxByOrNull { it.value.size }
                    ?.key
                    ?: sorted.first().merchant
                
                // Calculate intervals between transactions
                for (i in 1 until sorted.size) {
                    val days = TimePeriodUtils.daysBetween(sorted[i - 1].date, sorted[i].date)
                    intervalDays.add(days.toDouble())
                }
                
                if (intervalDays.isNotEmpty()) {
                    val avgIntervalDays = intervalDays.average()
                    // Check if intervals are consistent (within 7 days variance)
                    val intervalVarianceDaysSquared = intervalDays
                        .map { (it - avgIntervalDays) * (it - avgIntervalDays) }
                        .average()
                    
                    if (sqrt(intervalVarianceDaysSquared) <= ACCEPTABLE_REGULARITY_STDDEV_DAYS) {
                        // Regular pattern detected
                        val frequency = detectFrequency(avgIntervalDays)
                        val averageAmount = transactions.map { it.effectiveAmount }.average()
                        
                        recurring.add(
                            RecurringIncome(
                                source = merchant,
                                amount = averageAmount,
                                frequency = frequency,
                                nextExpectedDate = calculateNextDate(sorted.last().date, avgIntervalDays),
                                confidence = calculateConfidence(transactions.size, intervalVarianceDaysSquared),
                                transactionCount = transactions.size
                            )
                        )
                    }
                }
            }
        }
        
        recurring.sortedByDescending { it.confidence }
    }
    
    /**
     * Get total expected income for current month.
     */
    suspend fun getExpectedMonthlyIncome(): Double = withContext(Dispatchers.IO) {
        val recurring = detectRecurringIncome()
        var total = 0.0
        
        for (income in recurring) {
            when (income.frequency) {
                IncomeFrequency.WEEKLY -> total += income.amount * 4.33
                IncomeFrequency.BIWEEKLY -> total += income.amount * 2.17
                IncomeFrequency.MONTHLY -> total += income.amount
                IncomeFrequency.QUARTERLY -> total += income.amount / 3
                IncomeFrequency.YEARLY -> total += income.amount / 12
                IncomeFrequency.IRREGULAR -> total += income.amount / 3 // Estimate
            }
        }
        
        total
    }
    
    /**
     * Track income vs expenses ratio.
     */
    suspend fun getIncomeExpenseRatio(): IncomeExpenseRatio = withContext(Dispatchers.IO) {
        val now = timeProvider.now()
        val startOfMonth = TimePeriodUtils.getStartOfMonth(now)
        
        val expenses = expenseDao.getExpensesBetweenUncapped(startOfMonth, now)
        
        var income = 0.0
        var spending = 0.0
        
        for (expense in expenses) {
            val domainType = expense.transactionType.toDomain()
            when {
                domainType == DomainTransactionType.DEPOSIT -> income += expense.effectiveAmount
                domainType.isSpending -> spending += expense.effectiveAmount
                else -> { /* WITHDRAWAL / TRANSFER / UNKNOWN – not canonical spending */ }
            }
        }
        
        val savingsRate = if (income > 0) ((income - spending) / income) * 100 else 0.0
        
        IncomeExpenseRatio(
            totalIncome = income,
            totalExpenses = spending,
            savings = income - spending,
            savingsRate = savingsRate,
            isPositive = income > spending
        )
    }
    
    private fun detectFrequency(avgIntervalDays: Double): IncomeFrequency {
        return when {
            avgIntervalDays < 10 -> IncomeFrequency.WEEKLY
            avgIntervalDays < 20 -> IncomeFrequency.BIWEEKLY
            avgIntervalDays < 40 -> IncomeFrequency.MONTHLY
            avgIntervalDays < 100 -> IncomeFrequency.QUARTERLY
            avgIntervalDays < 400 -> IncomeFrequency.YEARLY
            else -> IncomeFrequency.IRREGULAR
        }
    }
    
    private fun calculateNextDate(lastDate: Long, averageIntervalDays: Double): Long {
        val roundedIntervalDays = averageIntervalDays.roundToInt().coerceAtLeast(1)
        return TimePeriodUtils.addDays(lastDate, roundedIntervalDays)
    }
    
    private fun calculateConfidence(count: Int, intervalVarianceDaysSquared: Double): Double {
        val countScore = minOf(count / 6.0, 0.5) // More occurrences = higher confidence
        val standardDeviationDays = sqrt(intervalVarianceDaysSquared)
        val varianceScore = when {
            standardDeviationDays <= HIGH_REGULARITY_STDDEV_DAYS -> 0.5
            standardDeviationDays <= MEDIUM_REGULARITY_STDDEV_DAYS -> 0.4
            standardDeviationDays <= ACCEPTABLE_REGULARITY_STDDEV_DAYS -> 0.3
            else -> 0.1
        }
        return minOf(countScore + varianceScore, 1.0)
    }

    private fun com.yourname.expensetracker.data.database.entity.TransactionType.toDomain(): DomainTransactionType =
        when (this) {
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }
}

data class RecurringIncome(
    val source: String,
    val amount: Double,
    val frequency: IncomeFrequency,
    val nextExpectedDate: Long,
    val confidence: Double,
    val transactionCount: Int
)

enum class IncomeFrequency {
    WEEKLY,
    BIWEEKLY,
    MONTHLY,
    QUARTERLY,
    YEARLY,
    IRREGULAR
}

data class IncomeExpenseRatio(
    val totalIncome: Double,
    val totalExpenses: Double,
    val savings: Double,
    val savingsRate: Double,
    val isPositive: Boolean
)
