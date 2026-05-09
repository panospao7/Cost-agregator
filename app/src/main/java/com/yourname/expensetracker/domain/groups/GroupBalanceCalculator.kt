package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.dao.GroupSettlementDao
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

    suspend fun calculateMemberBalance(groupId: Long, memberId: Long): GroupMemberBalance {
        val group = groupDao.getGroupById(groupId)
        val currency = group?.defaultCurrency ?: "EUR"
        val expenses = groupExpenseDao.getExpensesForGroupOnce(groupId)
        val settlements = settlementDao.getSettlementsForGroup(groupId)

        val paidTotal = expenses.filter { it.paidById == memberId }.sumOf { it.totalAmount }
        // Equal split: each member owes totalAmount / memberCount
        val memberCount = memberDao.getMemberCount(groupId).coerceAtLeast(1)
        val owedShareTotal = expenses.sumOf { it.totalAmount / memberCount }
        val settlementsPaid = settlements.filter { it.fromMemberId == memberId }.sumOf { it.amount }
        val settlementsReceived = settlements.filter { it.toMemberId == memberId }.sumOf { it.amount }
        val netBalance = paidTotal - owedShareTotal - settlementsPaid + settlementsReceived

        return GroupMemberBalance(groupId, memberId, currency, paidTotal, owedShareTotal, settlementsPaid, settlementsReceived, netBalance)
    }

    private companion object {
        private const val BALANCE_EPSILON = 0.01
    }
}
