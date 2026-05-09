package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.dao.GroupSettlementDao
import com.yourname.expensetracker.data.database.entity.SplitType
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
        val memberCount = memberDao.getMemberCount(groupId).coerceAtLeast(1)
        // For CUSTOM splits, compute owed share from the split JSON
        val owedShareTotal = expenses.sumOf { expense ->
            when (expense.splitType) {
                SplitType.EQUAL -> expense.totalAmount / memberCount
                SplitType.CUSTOM_PERCENT -> {
                    val json = expense.customSplitsJson
                    if (json != null) {
                        val percent = extractMemberShare(json, memberId.toString())
                        if (percent != null) expense.totalAmount * percent / 100.0
                        else expense.totalAmount / memberCount // missing member = equal share fallback
                    } else expense.totalAmount / memberCount
                }
                SplitType.CUSTOM_AMOUNT, SplitType.UNEQUAL -> {
                    val json = expense.customSplitsJson
                    if (json != null) {
                        val share = extractMemberShare(json, memberId.toString())
                        share ?: 0.0 // missing member in custom split = 0 (they owe nothing)
                    } else expense.totalAmount / memberCount
                }
                else -> expense.totalAmount / memberCount
            }
        }
        val settlementsPaid = settlements.filter { it.fromMemberId == memberId }.sumOf { it.amount }
        val settlementsReceived = settlements.filter { it.toMemberId == memberId }.sumOf { it.amount }
        val netBalance = paidTotal - owedShareTotal - settlementsPaid + settlementsReceived

        return GroupMemberBalance(groupId, memberId, currency, paidTotal, owedShareTotal, settlementsPaid, settlementsReceived, netBalance)
    }

    private fun extractMemberShare(customSplitsJson: String, memberIdStr: String): Double? {
        // Parse simple JSON: {"123": 45.0, "456": 55.0}
        val pattern = Regex("\"$memberIdStr\"\\s*:\\s*([\\d.]+)")
        return pattern.find(customSplitsJson)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private companion object {
        private const val BALANCE_EPSILON = 0.01
    }
}
