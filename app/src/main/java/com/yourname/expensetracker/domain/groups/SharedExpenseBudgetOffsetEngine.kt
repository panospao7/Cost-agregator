package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.GroupsRepository
import com.yourname.expensetracker.domain.logic.SplitCalculator
import com.yourname.expensetracker.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calculates effective budget spend on an accrual basis.
 *
 * Formula: budgetEffectiveSpend = personalSpend + sharedLiability
 * where sharedLiability = sum(myShareOfSharedExpenses)
 *
 * Note: reimbursements are cash-flow events and are intentionally excluded from budget spend.
 */
@Singleton
class SharedExpenseBudgetOffsetEngine @Inject constructor(
    private val groupsRepository: GroupsRepository,
    private val expenseRepository: ExpenseRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private companion object {
        private const val SETTLEMENT_EPSILON = 0.01
    }

    /**
     * Calculate the effective budget spend for a period on an accrual basis.
     *
     * @param periodStart Start of the period (inclusive) in milliseconds
     * @param periodEnd End of the period (exclusive) in milliseconds
     * @param categoryId Optional category filter (null for all categories)
     * @return BudgetSpendBreakdown with detailed breakdown of spend components
     */
    suspend fun calculateEffectiveBudgetSpend(
        periodStart: Long,
        periodEnd: Long,
        categoryId: Long? = null
    ): BudgetSpendBreakdown = withContext(ioDispatcher) {
        val allPeriodExpenses = expenseRepository.getExpensesBetween(periodStart, periodEnd)
        val expenseCategoryMap = allPeriodExpenses.associateBy { it.id }
        val activeGroups = groupsRepository.getActiveGroupsWithDetails()

        val inScopeGroupExpenses = activeGroups.mapNotNull { groupAggregate ->
            val currentUserMember = groupAggregate.members.find { it.isCurrentUser } ?: return@mapNotNull null
            InScopeGroupExpenses(
                currentUserMember = currentUserMember,
                members = groupAggregate.members,
                expenses = groupAggregate.expenses.filter {
                    it.date >= periodStart && it.date < periodEnd &&
                        (categoryId == null || it.expenseId?.let { expenseId ->
                            expenseCategoryMap[expenseId]?.categoryId
                        } == categoryId)
                }
            )
        }

        val linkedExpenseIds = inScopeGroupExpenses
            .flatMap { scope -> scope.expenses.mapNotNull { it.expenseId } }
            .toSet()

        val personalExpenses = allPeriodExpenses.filter { expense ->
            !expense.isSharedExpense &&
                !expense.isNotMine &&
                expense.transactionType == TransactionType.PURCHASE &&
                expense.id !in linkedExpenseIds &&
                (categoryId == null || expense.categoryId == categoryId)
        }

        val totalPersonalSpend = personalExpenses.sumOf { it.effectiveAmount }
        var totalSharedSpend = 0.0
        var totalReimbursed = 0.0

        for (scope in inScopeGroupExpenses) {
            for (groupExpense in scope.expenses) {
                if (SplitCalculator.isMemberParticipatingInSplit(
                        expense = groupExpense,
                        members = scope.members,
                        memberId = scope.currentUserMember.id
                    )) {
                    totalSharedSpend += SplitCalculator.calculateMemberShare(
                        expense = groupExpense,
                        members = scope.members,
                        memberId = scope.currentUserMember.id
                    )
                }

                if (groupExpense.isReimbursable && groupExpense.paidById == scope.currentUserMember.id) {
                    totalReimbursed += groupExpense.reimbursedAmount.coerceAtLeast(0.0)
                }
            }
        }

        val netSharedLiability = totalSharedSpend
        val effectiveBudgetSpend = totalPersonalSpend + totalSharedSpend

        BudgetSpendBreakdown(
            totalPersonalSpend = totalPersonalSpend,
            totalSharedSpend = totalSharedSpend,
            totalReimbursed = totalReimbursed,
            netSharedLiability = netSharedLiability,
            effectiveBudgetSpend = effectiveBudgetSpend
        )
    }

    /**
     * Check if an expense is fully settled (all reimbursements complete).
     */
    fun isExpenseFullySettled(expense: GroupExpense, members: List<GroupMember>): Boolean {
        if (!expense.isReimbursable) return true // Non-reimbursable expenses are always "settled"
        if (expense.settledAt != null) return true // Explicitly marked as settled

        // Check if total reimbursed equals what others owe
        val payerShare = if (SplitCalculator.isMemberParticipatingInSplit(
                expense = expense,
                members = members,
                memberId = expense.paidById
            )) {
            SplitCalculator.calculateMemberShare(
                expense = expense,
                members = members,
                memberId = expense.paidById
            )
        } else {
            0.0
        }
        val expectedReimbursement = expense.totalAmount - payerShare

        return expense.reimbursedAmount + SETTLEMENT_EPSILON >= expectedReimbursement
    }
}

private data class InScopeGroupExpenses(
    val currentUserMember: GroupMember,
    val members: List<GroupMember>,
    val expenses: List<GroupExpense>
)

/**
 * Breakdown of budget spend components.
 */
data class BudgetSpendBreakdown(
    val totalPersonalSpend: Double,      // Non-shared expenses
    val totalSharedSpend: Double,      // Sum of my shares in all shared expenses
    val totalReimbursed: Double,       // Sum of received reimbursements
    val netSharedLiability: Double,      // Accrual liability used for budgeting (equals sharedSpend)
    val effectiveBudgetSpend: Double     // Personal + sharedSpend (what counts against budget)
) {
    /**
     * Returns the amount pending reimbursement (positive = I'm owed money, negative = I owe money)
     */
    fun getPendingReimbursement(): Double = totalSharedSpend - totalReimbursed

    /**
     * Returns true if there are pending reimbursements affecting the budget
     */
    fun hasPendingReimbursements(): Boolean = kotlin.math.abs(getPendingReimbursement()) > 0.01
}
