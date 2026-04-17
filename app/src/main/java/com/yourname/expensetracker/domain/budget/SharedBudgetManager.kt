package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
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
    private val budgetCalculator: BudgetCalculator,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    
    /**
     * Calculate spending for a shared budget.
     *
     * B.2 Batch 3 fix: align shared-budget progress with canonical budget
     * semantics by using [BudgetCalculator.calculatePeriodRange] for the
     * active budget window and the same aggregate helpers used by budget
     * status calculations:
     *  - category budgets → [ExpenseDao.getCategorySpentInPeriod]
     *  - overall budgets → [ExpenseDao.getTotalForPeriod]
     *
     * Both helpers preserve effective-amount behavior and PURCHASE-only
     * budget spend semantics.
     */
    suspend fun getSharedBudgetProgress(
        budgetId: Long,
        memberIds: List<String>
    ): SharedBudgetProgress = withContext(ioDispatcher) {
        val budget = budgetRepository.getById(budgetId) ?: throw IllegalArgumentException("Budget not found")
        val now = timeProvider.now()
        val (periodStart, periodEnd) = budgetCalculator.calculatePeriodRange(budget, now)
        val elapsedEnd = now.coerceAtMost(periodEnd).coerceAtLeast(periodStart)
        val totalSpent = if (budget.categoryId != null) {
            expenseDao.getCategorySpentInPeriod(
                categoryId = budget.categoryId,
                startMs = periodStart,
                endMs = elapsedEnd
            )
        } else {
            expenseDao.getTotalForPeriod(
                startMs = periodStart,
                endMs = elapsedEnd
            )
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
    ): List<MemberContribution> = withContext(ioDispatcher) {
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
