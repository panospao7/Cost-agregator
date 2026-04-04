package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
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
    private val categoryRepository: CategoryRepository,
    private val savingsGoalRepository: SavingsGoalRepository,
    private val timeProvider: TimeProvider
) {
    private val monthlyCapMutex = Mutex()
    private val monthToDateRuleTotals = mutableMapOf<String, Double>()

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
            
            val cappedExecution = applyMonthlyCap(rule, execution)
            cappedExecution?.let { executions.add(it) }
        }
        
        return executions
    }
    
    private fun evaluatePercentageRule(
        expense: Expense,
        rule: AutomatedSavingsRule
    ): RuleExecution? {
        // Only process deposits (income)
        if (expense.transactionType != TransactionType.DEPOSIT || expense.amount <= 0) {
            return null
        }
        
        val percentage = rule.percentage ?: 10.0
        val normalizedIncome = expense.amount.coerceAtLeast(0.0)
        val amount = normalizedIncome * (percentage / 100.0)
        
        // Check minimum amount
        if (rule.minimumAmount != null && amount < rule.minimumAmount) {
            return null
        }
        
        return RuleExecution(
            rule = rule,
            amount = amount,
            reason = "${percentage}% of €${String.format("%.2f", normalizedIncome)} income",
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
        if (expense.transactionType != TransactionType.PURCHASE || expense.amount <= 0) {
            return null
        }

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
        val now = timeProvider.now()
        val weekStart = now - (7L * 24 * 60 * 60 * 1000)
        val weekExpenses = expenseRepository.getExpensesBetween(weekStart, now)
        val categoriesById = categoryRepository.getAll().associateBy { it.id }
        
        // Filter out essential spending (groceries, bills, etc.)
        var discretionarySpending = 0.0
        for (expense in weekExpenses) {
            if (
                expense.transactionType == TransactionType.PURCHASE &&
                expense.effectiveAmount > 0 &&
                !isEssentialCategory(expense.categoryId, categoriesById)
            ) {
                discretionarySpending += expense.effectiveAmount
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
    
    private suspend fun applyMonthlyCap(
        rule: AutomatedSavingsRule,
        execution: RuleExecution?
    ): RuleExecution? {
        val pendingExecution = execution ?: return null
        val cap = rule.maximumPerMonth ?: return pendingExecution
        if (cap <= 0.0) return null

        return monthlyCapMutex.withLock {
            val monthKey = buildMonthKey(pendingExecution.timestamp)
            val key = "${ruleStableKey(rule)}-$monthKey"
            val usedAmount = monthToDateRuleTotals[key] ?: 0.0
            val remainingAllowance = cap - usedAmount

            if (remainingAllowance <= 0.0) {
                return@withLock null
            }

            val allowedAmount = minOf(pendingExecution.amount, remainingAllowance)
            if (allowedAmount <= 0.0) {
                return@withLock null
            }

            monthToDateRuleTotals[key] = usedAmount + allowedAmount

            if (allowedAmount == pendingExecution.amount) {
                pendingExecution
            } else {
                pendingExecution.copy(
                    amount = allowedAmount,
                    reason = "${pendingExecution.reason} (monthly cap applied)"
                )
            }
        }
    }

    private fun ruleStableKey(rule: AutomatedSavingsRule): String {
        return if (rule.id > 0) {
            rule.id.toString()
        } else {
            "${rule.name}|${rule.ruleType}|${rule.targetGoalId}"
        }
    }

    private fun buildMonthKey(timestamp: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        return "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH)}"
    }

    private fun isEssentialCategory(
        categoryId: Long?,
        categoriesById: Map<Long, Category>
    ): Boolean {
        // Categories that don't count as discretionary
        val essentialCategories = setOf(
            "groceries", "rent", "utilities", "transport",
            "insurance", "healthcare", "bills", "mortgage", "education", "loan"
        )

        val categoryName = categoryId
            ?.let { categoriesById[it]?.name }
            ?.trim()
            ?.lowercase()

        return categoryName != null && categoryName in essentialCategories
    }
}
