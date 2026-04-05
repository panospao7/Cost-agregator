package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SplitCalculatorStressTest {

    @Test
    fun `int overflow for 25 million amount documents broken behavior from toCents int conversion`() {
        val members = members(1L, 2L)
        val expense = groupExpense(totalAmount = 25_000_000.00, paidById = 1L)

        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        val sum = splits.values.sum()
        assertApproxEquals(-8_974_836.48, splits[1L] ?: 0.0, 0.01)
        assertApproxEquals(-8_974_836.48, splits[2L] ?: 0.0, 0.01)
        assertApproxEquals(-17_949_672.96, sum, 0.01)
        assertFalse(SplitCalculator.validateSplits(splits, 25_000_000.00))
    }

    @Test
    fun `equal split with 100 members preserves sum and keeps every share finite`() {
        val members = (1L..100L).map { id ->
            GroupMember(id = id, groupId = 99L, name = "M$id", joinedAt = 0L)
        }
        val expense = groupExpense(totalAmount = 1000.00, paidById = 1L)

        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertEquals(100, splits.size)
        splits.values.forEach { share ->
            assertFalse(share.isNaN())
            assertFalse(share.isInfinite())
        }
        assertApproxEquals(1000.00, splits.values.sum(), 0.001)
    }

    @Test
    fun `zero amount equal split across members returns all zeros and preserves sum`() {
        val members = members(1L, 2L, 3L)
        val expense = groupExpense(totalAmount = 0.0, paidById = 1L)

        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertEquals(3, splits.size)
        assertApproxEquals(0.0, splits[1L] ?: 1.0, 0.0)
        assertApproxEquals(0.0, splits[2L] ?: 1.0, 0.0)
        assertApproxEquals(0.0, splits[3L] ?: 1.0, 0.0)
        assertApproxEquals(0.0, splits.values.sum(), 0.0)
    }

    private fun members(vararg ids: Long): List<GroupMember> =
        ids.map { id ->
            GroupMember(
                id = id,
                groupId = 1L,
                name = "Member$id",
                isCurrentUser = id == 1L,
                joinedAt = 0L
            )
        }

    private fun groupExpense(totalAmount: Double, paidById: Long): GroupExpense = GroupExpense(
        id = 1L,
        groupId = 1L,
        expenseId = null,
        paidById = paidById,
        date = 0L,
        description = "stress",
        totalAmount = totalAmount,
        splitType = SplitType.EQUAL,
        customSplitsJson = null
    )
}
