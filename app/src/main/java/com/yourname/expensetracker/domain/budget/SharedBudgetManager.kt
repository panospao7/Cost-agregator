package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages shared budgets across multiple users or groups.
 */
@Singleton
class SharedBudgetManager @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val expenseDao: ExpenseDao,
    private val timeProvider: TimeProvider
) {
    
    /**
     * Calculate spending for a shared budget.
     */
    suspend fun getSharedBudgetProgress(
        budgetId: Long,
        memberIds: List<String>
    ): SharedBudgetProgress = withContext(Dispatchers.IO) {
        val budget = budgetRepository.getById(budgetId) ?: throw IllegalArgumentException("Budget not found")
        val now = timeProvider.now()
        val startOfMonth = getStartOfMonth(now)
        
        // Get all expenses for this budget's category by members
        val expenses = expenseDao.getExpensesBetween(startOfMonth, now)
            .filter { expense ->
                expense.categoryId == budget.categoryId &&
                // Would need memberId field on expense in real implementation
                true
            }
        
        var totalSpent = 0.0
        for (expense in expenses) {
            totalSpent += expense.effectiveAmount
        }
        
        val remaining = budget.amount - totalSpent
        val percentUsed = if (budget.amount > 0) (totalSpent / budget.amount) * 100 else 0.0
        
        SharedBudgetProgress(
            budgetId = budgetId,
            budgetName = if (budget.categoryId != null) "Category ${budget.categoryId} Budget" else "Overall Budget",
            totalAmount = budget.amount,
            totalSpent = totalSpent,
            remaining = remaining,
            percentUsed = percentUsed,
            memberCount = memberIds.size,
            perMemberAverage = if (memberIds.isNotEmpty()) totalSpent / memberIds.size else 0.0,
            isOverBudget = totalSpent > budget.amount
        )
    }
    
    /**
     * Get individual member contributions to shared budget.
     */
    suspend fun getMemberContributions(
        budgetId: Long,
        memberIds: List<String>
    ): List<MemberContribution> = withContext(Dispatchers.IO) {
        // Simplified implementation - would need member tracking on expenses
        memberIds.map { memberId ->
            MemberContribution(
                memberId = memberId,
                memberName = "Member $memberId", // Would fetch actual name
                amountSpent = 0.0, // Would calculate from member-specific expenses
                percentOfTotal = 0.0,
                remainingAllowance = 0.0
            )
        }
    }
    
    private fun getStartOfMonth(timestamp: Long): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}

data class SharedBudgetProgress(
    val budgetId: Long,
    val budgetName: String,
    val totalAmount: Double,
    val totalSpent: Double,
    val remaining: Double,
    val percentUsed: Double,
    val memberCount: Int,
    val perMemberAverage: Double,
    val isOverBudget: Boolean
)

data class MemberContribution(
    val memberId: String,
    val memberName: String,
    val amountSpent: Double,
    val percentOfTotal: Double,
    val remainingAllowance: Double
)
