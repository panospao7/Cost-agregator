package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class AutomatedSavingsRule(
    val id: Long = 0,
    val name: String,
    val ruleType: SavingsRuleType,
    val targetGoalId: Long,
    val percentage: Double? = null,     // For PERCENTAGE_OF_INCOME
    val roundUpTo: Double? = null,      // For ROUND_UP (e.g., 5.0 for nearest €5)
    val minimumAmount: Double? = null,  // Minimum deposit to trigger
    val maximumPerMonth: Double? = null, // Cap monthly auto-savings
    val isActive: Boolean = true
)

enum class SavingsRuleType {
    PERCENTAGE_OF_INCOME,    // Save X% of every deposit
    ROUND_UP,               // Round up purchases to nearest €X
    SPARE_CHANGE,          // Save purchases under €10
    WEEKLY_NO_SPEND,        // Save €X on weeks with no discretionary spending
    CUSTOM
}

data class RuleExecution(
    val rule: AutomatedSavingsRule,
    val amount: Double,
    val reason: String,
    val timestamp: Long
)

@Singleton
class AutomatedSavingsRuleEngine @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val savingsGoalRepository: SavingsGoalRepository,
    private val timeProvider: TimeProvider
) {
    suspend fun evaluateRules(
        expense: Expense,
        rules: List<AutomatedSavingsRule>
    ): List<RuleExecution> {
        val executions = mutableListOf<RuleExecution>()
        
        for (rule in rules) {
            if (!rule.isActive) continue
            
            val execution = when (rule.ruleType) {
                SavingsRuleType.PERCENTAGE_OF_INCOME -> 
                    evaluatePercentageRule(expense, rule)
                SavingsRuleType.ROUND_UP -> 
                    evaluateRoundUpRule(expense, rule)
                SavingsRuleType.SPARE_CHANGE -> 
                    evaluateSpareChangeRule(expense, rule)
                SavingsRuleType.WEEKLY_NO_SPEND -> 
                    evaluateWeeklyNoSpendRule(rule)
                SavingsRuleType.CUSTOM -> null
            }
            
            execution?.let { executions.add(it) }
        }
        
        return executions
    }
    
    private fun evaluatePercentageRule(
        expense: Expense,
        rule: AutomatedSavingsRule
    ): RuleExecution? {
        // Only process deposits (income)
        if (expense.transactionType != TransactionType.DEPOSIT && expense.amount >= 0) {
            return null
        }
        
        val percentage = rule.percentage ?: 10.0
        val amount = kotlin.math.abs(expense.amount) * (percentage / 100.0)
        
        // Check minimum amount
        if (rule.minimumAmount != null && amount < rule.minimumAmount) {
            return null
        }
        
        return RuleExecution(
            rule = rule,
            amount = amount,
            reason = "${percentage}% of €${String.format("%.2f", kotlin.math.abs(expense.amount))} income",
            timestamp = timeProvider.now()
        )
    }
    
    private fun evaluateRoundUpRule(
        expense: Expense,
        rule: AutomatedSavingsRule
    ): RuleExecution? {
        // Only process purchases
        if (expense.transactionType != TransactionType.PURCHASE || expense.amount <= 0) {
            return null
        }
        
        val roundUpTo = rule.roundUpTo ?: 5.0
        val remainder = expense.amount % roundUpTo
        
        if (remainder > 0) {
            val roundUpAmount = roundUpTo - remainder
            
            return RuleExecution(
                rule = rule,
                amount = roundUpAmount,
                reason = "Round up €${String.format("%.2f", expense.amount)} to €${String.format("%.2f", kotlin.math.ceil(expense.amount / roundUpTo) * roundUpTo)}",
                timestamp = timeProvider.now()
            )
        }
        
        return null
    }
    
    private fun evaluateSpareChangeRule(
        expense: Expense,
        rule: AutomatedSavingsRule
    ): RuleExecution? {
        // Save small purchases (coffee, snacks, etc.)
        if (expense.amount in 1.0..10.0) {
            return RuleExecution(
                rule = rule,
                amount = expense.amount,
                reason = "Spare change: ${expense.merchant} €${String.format("%.2f", expense.amount)}",
                timestamp = timeProvider.now()
            )
        }
        return null
    }
    
    private suspend fun evaluateWeeklyNoSpendRule(
        rule: AutomatedSavingsRule
    ): RuleExecution? {
        // Check if this week had any discretionary spending
        val weekStart = timeProvider.now() - (7 * 24 * 60 * 60 * 1000)
        val weekExpenses = expenseRepository.getExpensesBetween(weekStart, timeProvider.now())
        
        // Filter out essential spending (groceries, bills, etc.)
        var discretionarySpending = 0.0
        for (expense in weekExpenses) {
            if (expense.amount > 0 && !isEssentialCategory(expense.categoryId)) {
                discretionarySpending += expense.amount
            }
        }
        
        // If no discretionary spending, award savings
        if (discretionarySpending < 5.0) {
            val rewardAmount = 10.0 // €10 reward for no-spend week
            
            return RuleExecution(
                rule = rule,
                amount = rewardAmount,
                reason = "No discretionary spending this week! 🎉",
                timestamp = timeProvider.now()
            )
        }
        
        return null
    }
    
    private fun isEssentialCategory(categoryId: Long?): Boolean {
        // Categories that don't count as discretionary
        val essentialCategories = listOf(
            "Groceries", "Rent", "Utilities", "Transport", 
            "Insurance", "Healthcare", "Bills"
        )
        // This would need category name lookup in real implementation
        return false // Simplified for now
    }
}
