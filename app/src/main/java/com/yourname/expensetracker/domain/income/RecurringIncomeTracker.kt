package com.yourname.expensetracker.domain.income

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks and analyzes recurring income patterns.
 */
@Singleton
class RecurringIncomeTracker @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val expenseRepository: ExpenseRepository,
    private val timeProvider: TimeProvider
) {
    
    /**
     * Detect recurring income patterns from deposit transactions.
     */
    suspend fun detectRecurringIncome(): List<RecurringIncome> = withContext(Dispatchers.IO) {
        val threeMonthsAgo = timeProvider.now() - (90L * 24 * 60 * 60 * 1000)
        val deposits = expenseDao.getExpensesByTypeBetween(
            threeMonthsAgo,
            timeProvider.now(),
            DomainTransactionType.DEPOSIT.name
        )
        
        // Group by merchant/payer
        val merchantGroups = deposits.groupBy { it.merchant }
        
        val recurring = mutableListOf<RecurringIncome>()
        
        for ((merchant, transactions) in merchantGroups) {
            if (transactions.size >= 2) { // At least 2 occurrences
                val sorted = transactions.sortedBy { it.date }
                val intervals = mutableListOf<Long>()
                
                // Calculate intervals between transactions
                for (i in 1 until sorted.size) {
                    intervals.add(sorted[i].date - sorted[i-1].date)
                }
                
                if (intervals.isNotEmpty()) {
                    val avgInterval = intervals.average()
                    // Check if intervals are consistent (within 7 days variance)
                    val variance = intervals.map { (it - avgInterval) * (it - avgInterval) }.average()
                    
                    if (variance < (7 * 24 * 60 * 60 * 1000L).let { it * it }) {
                        // Regular pattern detected
                        val frequency = detectFrequency(avgInterval)
                        val averageAmount = transactions.map { it.effectiveAmount }.average()
                        
                        recurring.add(
                            RecurringIncome(
                                source = merchant,
                                amount = averageAmount,
                                frequency = frequency,
                                nextExpectedDate = calculateNextDate(sorted.last().date, avgInterval),
                                confidence = calculateConfidence(transactions.size, variance),
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
        val startOfMonth = getStartOfMonth(timeProvider.now())
        val now = timeProvider.now()
        
        val expenses = expenseDao.getExpensesBetween(startOfMonth, now)
        
        var income = 0.0
        var spending = 0.0
        
        for (expense in expenses) {
            when (expense.transactionType.toDomain()) {
                DomainTransactionType.DEPOSIT -> income += expense.effectiveAmount
                DomainTransactionType.PURCHASE, DomainTransactionType.WITHDRAWAL -> spending += expense.effectiveAmount
                else -> {}
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
    
    private fun detectFrequency(avgIntervalMs: Double): IncomeFrequency {
        val days = avgIntervalMs / (24 * 60 * 60 * 1000)
        return when {
            days < 10 -> IncomeFrequency.WEEKLY
            days < 20 -> IncomeFrequency.BIWEEKLY
            days < 40 -> IncomeFrequency.MONTHLY
            days < 100 -> IncomeFrequency.QUARTERLY
            days < 400 -> IncomeFrequency.YEARLY
            else -> IncomeFrequency.IRREGULAR
        }
    }
    
    private fun calculateNextDate(lastDate: Long, interval: Double): Long {
        return lastDate + interval.toLong()
    }
    
    private fun calculateConfidence(count: Int, variance: Double): Double {
        val countScore = minOf(count / 6.0, 0.5) // More occurrences = higher confidence
        val varianceScore = if (variance < 1_000_000_000) 0.5 else 0.3 // Lower variance = higher confidence
        return minOf(countScore + varianceScore, 1.0)
    }
    
    private fun getStartOfMonth(timestamp: Long): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        return calendar.timeInMillis
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
