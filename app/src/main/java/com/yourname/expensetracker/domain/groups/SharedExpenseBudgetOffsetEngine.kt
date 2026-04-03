package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.GroupsRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
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
    private val sharedExpenseManager: SharedExpenseManager,
    private val timeProvider: TimeProvider
) {
    /**
     * Calculate the effective budget spend for a period on an accrual basis.
     *
     * @param periodStart Start of the period (inclusive) in milliseconds
     * @param periodEnd End of the period (exclusive) in milliseconds
     * @param categoryId Optional category filter (null for all categories)
     * @param userId User identifier for current user (default "default")
     * @return BudgetSpendBreakdown with detailed breakdown of spend components
     */
    suspend fun calculateEffectiveBudgetSpend(
        periodStart: Long,
        periodEnd: Long,
        categoryId: Long? = null,
        userId: String = "default"
    ): BudgetSpendBreakdown = withContext(Dispatchers.IO) {
        try {
            val allPeriodExpenses = expenseRepository.getExpensesBetween(periodStart, periodEnd)
            val expenseCategoryMap = allPeriodExpenses.associateBy { it.id }

            // Get personal (non-shared) expenses for the period
            val personalExpenses = allPeriodExpenses
                .filter { expense ->
                    // Filter out shared expenses linked to group expenses
                    !expense.isSharedExpense &&
                    !expense.isNotMine &&
                    expense.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE &&
                    (categoryId == null || expense.categoryId == categoryId)
                }

            val totalPersonalSpend = personalExpenses.sumOf { it.amount }

            // Get all active groups with details
            val activeGroups = groupsRepository.getActiveGroupsWithDetails()

            var totalSharedSpend = 0.0
            var totalReimbursed = 0.0

            // Process each group
            for (groupAggregate in activeGroups) {
                val members = groupAggregate.members
                val expenses = groupAggregate.expenses

                // Find current user member in this group
                val currentUserMember = members.find { it.isCurrentUser }
                    ?: continue // Skip if current user not in this group

                // Filter expenses for the period
                val periodExpenses = expenses.filter {
                    it.date >= periodStart && it.date < periodEnd &&
                    (categoryId == null || it.expenseId?.let { expId ->
                        expenseCategoryMap[expId]?.categoryId
                    } == categoryId)
                }

                for (groupExpense in periodExpenses) {
                    // Calculate my share of this expense
                    val myShare = calculateMyShare(groupExpense, members, currentUserMember.id)
                    totalSharedSpend += myShare

                    // Track reimbursements for informational breakdown only (not budget offset)
                    if (groupExpense.isReimbursable && groupExpense.paidById == currentUserMember.id) {
                        totalReimbursed += groupExpense.reimbursedAmount.coerceAtLeast(0.0)
                    }
                }
            }

            // Accrual accounting for budgets: count what the user owes (their share), not cash reimbursements.
            val netSharedLiability = totalSharedSpend
            val effectiveBudgetSpend = totalPersonalSpend + totalSharedSpend

            BudgetSpendBreakdown(
                totalPersonalSpend = totalPersonalSpend,
                totalSharedSpend = totalSharedSpend,
                totalReimbursed = totalReimbursed,
                netSharedLiability = netSharedLiability,
                effectiveBudgetSpend = effectiveBudgetSpend
            )
        } catch (e: Exception) {
            Timber.e(e, "Error calculating effective budget spend")
            // Return zeroed breakdown on error
            BudgetSpendBreakdown(
                totalPersonalSpend = 0.0,
                totalSharedSpend = 0.0,
                totalReimbursed = 0.0,
                netSharedLiability = 0.0,
                effectiveBudgetSpend = 0.0
            )
        }
    }

    /**
     * Calculate the user's share of a group expense.
     */
    private fun calculateMyShare(
        expense: GroupExpense,
        members: List<com.yourname.expensetracker.data.database.entity.GroupMember>,
        currentUserMemberId: Long
    ): Double {
        return when (expense.splitType) {
            SplitType.EQUAL -> {
                if (members.isEmpty()) 0.0
                else expense.totalAmount / members.size
            }
            SplitType.CUSTOM_AMOUNT -> {
                parseCustomSplits(expense.customSplitsJson)[currentUserMemberId] ?: 0.0
            }
            SplitType.CUSTOM_PERCENT -> {
                val percentage = parseCustomSplits(expense.customSplitsJson)[currentUserMemberId] ?: 0.0
                expense.totalAmount * (percentage / 100.0)
            }
            SplitType.UNEQUAL -> {
                parseCustomSplits(expense.customSplitsJson)[currentUserMemberId] ?: 0.0
            }
        }
    }

    /**
     * Parse custom splits JSON string.
     * Format: "memberId:amount,memberId:amount"
     */
    private fun parseCustomSplits(splitsString: String?): Map<Long, Double> {
        if (splitsString.isNullOrBlank()) return emptyMap()

        val result = mutableMapOf<Long, Double>()
        val pairs = splitsString.split(",")
        for (pair in pairs) {
            val parts = pair.split(":")
            if (parts.size == 2) {
                val memberId = parts[0].toLongOrNull()
                val amount = parts[1].toDoubleOrNull()
                if (memberId != null && amount != null) {
                    result[memberId] = amount
                }
            }
        }
        return result
    }

    /**
     * Check if an expense is fully settled (all reimbursements complete).
     */
    fun isExpenseFullySettled(expense: GroupExpense, members: List<com.yourname.expensetracker.data.database.entity.GroupMember>): Boolean {
        if (!expense.isReimbursable) return true // Non-reimbursable expenses are always "settled"
        if (expense.settledAt != null) return true // Explicitly marked as settled

        // Check if total reimbursed equals what others owe
        val myShare = expense.myShareAmount ?: (expense.totalAmount / members.size)
        val expectedReimbursement = expense.totalAmount - myShare

        return expense.reimbursedAmount >= expectedReimbursement
    }
}

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
