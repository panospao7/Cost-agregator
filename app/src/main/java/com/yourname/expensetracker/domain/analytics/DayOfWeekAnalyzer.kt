package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DayOfWeekAnalyzer @Inject constructor() {

    companion object {
        private val DAY_NAMES = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }

    fun analyze(
        startDate: Long,
        endDate: Long,
        allExpenses: List<ExpenseSnapshot>,
        displayCurrency: String = "EUR"
    ): List<DayOfWeekInsight> {
        val expenses = allExpenses.filter { 
            it.date >= startDate && 
            it.date < endDate &&
            it.transactionType == DomainTransactionType.PURCHASE && 
            !it.isNotMine 
        }

        val byDayOfWeek = expenses.groupBy { expense ->
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = expense.date
            // Calendar.DAY_OF_WEEK: Sunday=1, Monday=2, ..., Saturday=7
            // Convert to Monday=0, Tuesday=1, ..., Sunday=6 to match DAY_NAMES
            (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        }

        // Keep stable chronological weekday order (Mon -> Sun).
        return DAY_NAMES.indices.map { dayIndex ->
            val dayExpenses = byDayOfWeek[dayIndex] ?: emptyList()
            // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
            val total = dayExpenses.sumOf { it.effectiveAmount }
            val count = dayExpenses.size

            DayOfWeekInsight(
                dayName = DAY_NAMES[dayIndex],
                dayIndex = dayIndex,
                totalSpent = total,
                transactionCount = count,
                avgPerTransaction = if (count > 0) total / count else 0.0,
                displayCurrency = displayCurrency
            )
        }
    }


}
