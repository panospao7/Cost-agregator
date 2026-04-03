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
 * Calculates effective budget spend excluding reimbursable shared expenses.
 *
 * Formula: budgetEffectiveSpend = personalSpend + netSharedLiability
 * where netSharedLiability = sum(myShareOfSharedExpenses) - sum(receivedReimbursements)
 */
@Singleton
class SharedExpenseBudgetOffsetEngine @Inject constructor(
    private val groupsRepository: GroupsRepository,
    private val expenseRepository: ExpenseRepository,
    private val sharedExpenseManager: SharedExpenseManager,
    private val timeProvider: TimeProvider
) {
    /**
     * Calculate the effective budget spend for a period, excluding reimbursable shared expenses.
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
            // Get personal (non-shared) expenses for the period
            val personalExpenses = expenseRepository.getExpensesBetween(periodStart, periodEnd)
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
            var netSharedLiability = 0.0

            // Process each group
            for (groupAggregate in activeGroups) {
                val group = groupAggregate.group
                val members = groupAggregate.members
                val expenses = groupAggregate.expenses

                // Find current user member in this group
                val currentUserMember = members.find { it.isCurrentUser }
                    ?: continue // Skip if current user not in this group

                // Filter expenses for the period
                val periodExpenses = expenses.filter {
                    it.date >= periodStart && it.date < periodEnd &&
                    (categoryId == null || it.expenseId?.let { expId ->
                        expenseRepository.getExpensesBetween(periodStart, periodEnd)
                            .find { e -> e.id == expId }?.categoryId
                    } == categoryId)
                }

                for (groupExpense in periodExpenses) {
                    // Calculate my share of this expense
                    val myShare = calculateMyShare(groupExpense, members, currentUserMember.id)
                    totalSharedSpend += myShare

                    // Calculate reimbursed amount for this expense
                    val reimbursedAmount = if (groupExpense.isReimbursable) {
                        calculateReimbursedAmount(groupExpense, currentUserMember.id)
                    } else {
                        0.0
                    }
                    totalReimbursed += reimbursedAmount

                    // Net liability = my share - what I've been reimbursed
                    val netLiability = myShare - reimbursedAmount
                    netSharedLiability += netLiability
                }
            }

            // Effective budget spend includes personal spend + net shared liability
            val effectiveBudgetSpend = totalPersonalSpend + netSharedLiability

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
     * Calculate the reimbursed amount for the current user on a specific expense.
     * For now, uses the stored reimbursedAmount field.
     * In the future, this could track individual reimbursements from other members.
     */
    private fun calculateReimbursedAmount(
        expense: GroupExpense,
        currentUserMemberId: Long
    ): Double {
        // If this is the payer, they've been "reimbursed" by not having to pay their share
        // If this is another member, check the reimbursed amount
        return if (expense.paidById == currentUserMemberId) {
            // Payer gets "reimbursed" by the difference between total and their share
            val myShare = expense.myShareAmount ?: (expense.totalAmount / 2.0) // Default to 50% if not set
            expense.totalAmount - myShare
        } else {
            // Non-payer uses the stored reimbursed amount
            expense.reimbursedAmount
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
    val netSharedLiability: Double,      // What I actually owe (sharedSpend - reimbursed)
    val effectiveBudgetSpend: Double     // Personal + netSharedLiability (what counts against budget)
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
