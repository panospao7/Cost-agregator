package com.yourname.expensetracker.domain.cashflow

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

data class DailyCashFlow(
    val date: Date,
    val startingBalance: Double,
    val income: List<Expense>,
    val expenses: List<Expense>,
    val predictedRecurring: List<RecurringPattern>,
    val endingBalance: Double,
    val riskLevel: CashFlowRiskLevel
)

enum class CashFlowRiskLevel {
    NONE,      // Healthy surplus
    LOW,       // Slight surplus
    MEDIUM,    // Near break-even
    HIGH       // Risk of going negative
}

@Singleton
class CashFlowCalculator @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val recurringExpenseEngine: RecurringExpenseEngine,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val timeProvider: TimeProvider
) {
    suspend fun calculateDailyCashFlow(
        startDate: Date,
        endDate: Date,
        startingBalance: Double = 0.0
    ): List<DailyCashFlow> {
        val calendar = Calendar.getInstance()
        val results = mutableListOf<DailyCashFlow>()
        var runningBalance = startingBalance
        
        // Get historical data for the period
        val startTime = startDate.time
        val endTime = endDate.time
        
        val historicalExpenses = expenseRepository.getExpensesBetween(startTime, endTime)
        
        // Get recurring patterns for prediction
        val allExpensesFlow = expenseRepository.getAllExpenses().first()
        val recurringPatterns = recurringExpenseEngine.getPatterns(allExpensesFlow)
        
        // Group historical expenses by day manually
        val expensesByDay = mutableMapOf<Int, MutableList<Expense>>()
        for (expense in historicalExpenses) {
            calendar.timeInMillis = expense.date
            val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
            val list = expensesByDay.getOrPut(dayOfYear) { mutableListOf() }
            list.add(expense)
        }
        
        // Process each day
        calendar.time = startDate
        while (calendar.time.before(endDate) || calendar.time == endDate) {
            val currentDay = calendar.time
            val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
            
            // Get day's expenses
            val dayExpenses = expensesByDay[dayOfYear] ?: mutableListOf()
            
            // Split into income and expenses manually
            val incomeList = mutableListOf<Expense>()
            val expenseList = mutableListOf<Expense>()
            for (expense in dayExpenses) {
                if (expense.transactionType == TransactionType.DEPOSIT || expense.amount < 0) {
                    incomeList.add(expense)
                } else {
                    expenseList.add(expense)
                }
            }
            
            // Calculate predicted recurring for this day
            val predictedRecurringList = mutableListOf<RecurringPattern>()
            for (pattern in recurringPatterns) {
                if (pattern.nextExpectedDate >= (currentDay.time - 86400000) && 
                    pattern.nextExpectedDate <= (currentDay.time + 86400000)) {
                    predictedRecurringList.add(pattern)
                }
            }
            
            // Calculate ending balance
            var dayIncome = 0.0
            for (inc in incomeList) {
                dayIncome += Math.abs(inc.amount)
            }
            
            var dayExpensesTotal = 0.0
            for (exp in expenseList) {
                dayExpensesTotal += exp.amount
            }
            for (recurring in predictedRecurringList) {
                dayExpensesTotal += recurring.averageAmount
            }
            
            runningBalance = runningBalance + dayIncome - dayExpensesTotal
            
            // Determine risk level
            val riskLevel = when {
                runningBalance > 500 -> CashFlowRiskLevel.NONE
                runningBalance > 100 -> CashFlowRiskLevel.LOW
                runningBalance > 0 -> CashFlowRiskLevel.MEDIUM
                else -> CashFlowRiskLevel.HIGH
            }
            
            results.add(
                DailyCashFlow(
                    date = currentDay,
                    startingBalance = runningBalance - dayIncome + dayExpensesTotal,
                    income = incomeList,
                    expenses = expenseList,
                    predictedRecurring = predictedRecurringList,
                    endingBalance = runningBalance,
                    riskLevel = riskLevel
                )
            )
            
            // Move to next day
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        
        return results
    }
    
    suspend fun getUpcomingBills(daysAhead: Int): List<RecurringPattern> {
        val allExpenses = expenseRepository.getAllExpenses().first()
        val patterns = recurringExpenseEngine.getPatterns(allExpenses)
        
        val now = timeProvider.now()
        val future = now + (daysAhead * 24 * 60 * 60 * 1000L)
        
        val upcomingList = mutableListOf<RecurringPattern>()
        for (pattern in patterns) {
            if (pattern.nextExpectedDate >= now && pattern.nextExpectedDate <= future) {
                upcomingList.add(pattern)
            }
        }
        
        return upcomingList
    }
}
