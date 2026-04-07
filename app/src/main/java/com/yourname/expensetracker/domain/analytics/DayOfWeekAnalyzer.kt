package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.model.DomainTransactionType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DayOfWeekAnalyzer @Inject constructor() {

    companion object {
        private val DAY_NAMES = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }

    fun analyze(startDate: Long, endDate: Long, allExpenses: List<Expense>): List<DayOfWeekInsight> {
        val expenses = allExpenses.filter { 
            it.date != null &&
            it.date >= startDate && 
            it.date < endDate &&
            it.transactionType.toDomain() == DomainTransactionType.PURCHASE && 
            !it.isNotMine 
        }
        
        val byDayOfWeek = expenses.groupBy { expense ->
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = expense.date!!
            // Calendar.DAY_OF_WEEK: Sunday=1, Monday=2, ..., Saturday=7
            // Convert to Monday=0, Tuesday=1, ..., Sunday=6 to match DAY_NAMES
            (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        }
        
        return (0..6).map { dayIndex ->
            val dayExpenses = byDayOfWeek[dayIndex] ?: emptyList()
            val total = dayExpenses.sumOf { it.effectiveAmount }
            val count = dayExpenses.size
            
            DayOfWeekInsight(
                dayName = DAY_NAMES[dayIndex],
                dayIndex = dayIndex,
                totalSpent = total,
                transactionCount = count,
                avgPerTransaction = if (count > 0) total / count else 0.0
            )
        }.sortedByDescending { it.totalSpent }
    }

    // Boundary mapper: data-layer TransactionType -> domain DomainTransactionType
    private fun com.yourname.expensetracker.data.database.entity.TransactionType.toDomain(): DomainTransactionType =
        when (this) {
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }
}
