package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.dao.GroupSettlementDao
import com.yourname.expensetracker.domain.logic.SplitCalculator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupBalanceCalculator @Inject constructor(
    private val groupDao: ExpenseGroupDao,
    private val memberDao: GroupMemberDao,
    private val groupExpenseDao: GroupExpenseDao,
    private val settlementDao: GroupSettlementDao
) {
    data class GroupMemberBalance(
        val groupId: Long,
        val memberId: Long,
        val currency: String,
        val paidTotal: Double,
        val owedShareTotal: Double,
        val settlementsPaid: Double,
        val settlementsReceived: Double,
        val netBalance: Double
    ) {
        val isSettled: Boolean get() = kotlin.math.abs(netBalance) <= BALANCE_EPSILON
    }

    /**
     * Calculates the balance for a single member using historical participation.
     * All members are loaded so [SplitCalculator] can apply joinedAt/leftAt per expense date.
     */
    suspend fun calculateMemberBalance(groupId: Long, memberId: Long): GroupMemberBalance {
        val group = groupDao.getGroupById(groupId)
        val currency = group?.defaultCurrency ?: "EUR"
        val expenses = groupExpenseDao.getExpensesForGroupOnce(groupId)
        val members = memberDao.getAllForGroup(groupId)
        val settlements = settlementDao.getSettlementsForGroup(groupId)

        val paidTotal = expenses.filter { it.paidById == memberId }.sumOf { it.totalAmount }

        // E4-NOW-004 FIX: Use SplitCalculator which respects joinedAt for historical participation
        val owedShareTotal = expenses.sumOf { expense ->
            SplitCalculator.calculateMemberShare(
                expense = expense,
                members = members,
                memberId = memberId
            )
        }

        // E4-NOW-005 FIX: Only count settlements with status RECORDED or COMPLETED and matching currency
        val validSettlements = settlements.filter {
            it.status in listOf("RECORDED", "COMPLETED") && it.currency == currency
        }
        val settlementsPaid = validSettlements.filter { it.fromMemberId == memberId }.sumOf { it.amount }
        val settlementsReceived = validSettlements.filter { it.toMemberId == memberId }.sumOf { it.amount }
        val netBalance = paidTotal - owedShareTotal - settlementsPaid + settlementsReceived

        return GroupMemberBalance(groupId, memberId, currency, paidTotal, owedShareTotal, settlementsPaid, settlementsReceived, netBalance)
    }

    private companion object {
        private const val BALANCE_EPSILON = 0.01
    }
}
