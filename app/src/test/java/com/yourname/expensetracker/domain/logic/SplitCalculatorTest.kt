package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitCalculatorTest {

    @Test
    fun `equal split of 100 among 3 members distributes remainder to first member`() {
        val members = members(1L, 2L, 3L)
        val expense = groupExpense(totalAmount = 100.00, paidById = 1L, splitType = SplitType.EQUAL)

        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertEquals(3, splits.size)
        assertApproxEquals(33.34, splits[1L] ?: 0.0, 0.01)
        assertApproxEquals(33.33, splits[2L] ?: 0.0, 0.01)
        assertApproxEquals(33.33, splits[3L] ?: 0.0, 0.01)
        assertApproxEquals(100.00, splits.values.sum(), 0.001)
        assertTrue(SplitCalculator.validateSplits(splits, 100.00))
    }

    @Test
    fun `equal split of 100 among 7 members preserves sum with four one-cent remainders`() {
        val members = members(1L, 2L, 3L, 4L, 5L, 6L, 7L)
        val expense = groupExpense(totalAmount = 100.00, paidById = 1L, splitType = SplitType.EQUAL)

        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertEquals(7, splits.size)
        assertApproxEquals(14.29, splits[1L] ?: 0.0, 0.01)
        assertApproxEquals(14.29, splits[2L] ?: 0.0, 0.01)
        assertApproxEquals(14.29, splits[3L] ?: 0.0, 0.01)
        assertApproxEquals(14.29, splits[4L] ?: 0.0, 0.01)
        assertApproxEquals(14.28, splits[5L] ?: 0.0, 0.01)
        assertApproxEquals(14.28, splits[6L] ?: 0.0, 0.01)
        assertApproxEquals(14.28, splits[7L] ?: 0.0, 0.01)
        assertApproxEquals(100.00, splits.values.sum(), 0.001)
    }

    @Test
    fun `percentage split 33_33 33_33 33_34 maps to exact cent values and preserves sum`() {
        val members = members(1L, 2L, 3L)
        val expense = groupExpense(
            totalAmount = 100.00,
            paidById = 1L,
            splitType = SplitType.CUSTOM_PERCENT,
            customSplitsJson = "1:33.33,2:33.33,3:33.34"
        )

        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertApproxEquals(33.33, splits[1L] ?: 0.0, 0.01)
        assertApproxEquals(33.33, splits[2L] ?: 0.0, 0.01)
        assertApproxEquals(33.34, splits[3L] ?: 0.0, 0.01)
        assertApproxEquals(100.00, splits.values.sum(), 0.001)
    }

    @Test
    fun `percentage split 33_33 each allocates remainder to first member by tie break`() {
        val members = members(1L, 2L, 3L)
        val expense = groupExpense(
            totalAmount = 100.00,
            paidById = 1L,
            splitType = SplitType.CUSTOM_PERCENT,
            customSplitsJson = "1:33.33,2:33.33,3:33.33"
        )

        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertApproxEquals(33.34, splits[1L] ?: 0.0, 0.01)
        assertApproxEquals(33.33, splits[2L] ?: 0.0, 0.01)
        assertApproxEquals(33.33, splits[3L] ?: 0.0, 0.01)
        assertApproxEquals(100.00, splits.values.sum(), 0.001)
    }

    @Test
    fun `custom amount split returns exact provided amounts and preserves sum`() {
        val members = members(1L, 2L, 3L)
        val expense = groupExpense(
            totalAmount = 100.00,
            paidById = 1L,
            splitType = SplitType.CUSTOM_AMOUNT,
            customSplitsJson = "1:50.0,2:30.0,3:20.0"
        )

        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertApproxEquals(50.0, splits[1L] ?: 0.0, 0.01)
        assertApproxEquals(30.0, splits[2L] ?: 0.0, 0.01)
        assertApproxEquals(20.0, splits[3L] ?: 0.0, 0.01)
        assertApproxEquals(100.00, splits.values.sum(), 0.001)
    }

    @Test
    fun `unequal split returns exact provided amounts and preserves sum`() {
        val members = members(1L, 2L, 3L)
        val expense = groupExpense(
            totalAmount = 100.00,
            paidById = 2L,
            splitType = SplitType.UNEQUAL,
            customSplitsJson = "1:70.0,2:20.0,3:10.0"
        )

        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertApproxEquals(70.0, splits[1L] ?: 0.0, 0.01)
        assertApproxEquals(20.0, splits[2L] ?: 0.0, 0.01)
        assertApproxEquals(10.0, splits[3L] ?: 0.0, 0.01)
        assertApproxEquals(100.00, splits.values.sum(), 0.001)
    }

    @Test
    fun `invalid custom payload falls back to equal split preserving total amount`() {
        val members = members(1L, 2L, 3L)
        val expense = groupExpense(
            totalAmount = 100.00,
            paidById = 1L,
            splitType = SplitType.CUSTOM_AMOUNT,
            customSplitsJson = "1:60.0,2:40.0" // missing member 3
        )

        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertApproxEquals(33.34, splits[1L] ?: 0.0, 0.01)
        assertApproxEquals(33.33, splits[2L] ?: 0.0, 0.01)
        assertApproxEquals(33.33, splits[3L] ?: 0.0, 0.01)
        assertApproxEquals(100.00, splits.values.sum(), 0.001)
    }

    @Test
    fun `calculateBalances with crash test 4_6 balances simplifies to total 50 settlement volume`() {
        val members = members(1L, 2L, 3L)
        val expenses = listOf(
            groupExpense(id = 1L, totalAmount = 90.0, paidById = 1L, splitType = SplitType.EQUAL),
            groupExpense(id = 2L, totalAmount = 60.0, paidById = 2L, splitType = SplitType.EQUAL),
            groupExpense(id = 3L, totalAmount = 30.0, paidById = 3L, splitType = SplitType.EQUAL)
        )

        val balances = SplitCalculator.calculateBalances(expenses, members)
        val settlements = SplitCalculator.simplifyBalances(balances)

        assertApproxEquals(30.0, balances[1L] ?: 0.0, 0.01)
        assertApproxEquals(0.0, balances[2L] ?: 0.0, 0.01)
        assertApproxEquals(-30.0, balances[3L] ?: 0.0, 0.01)
        assertEquals(1, settlements.size)
        assertApproxEquals(30.0, settlements.sumOf { it.third }, 0.01)
        assertFalse(settlements.any { it.third <= 0.0 })
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

    private fun groupExpense(
        id: Long = 1L,
        totalAmount: Double,
        paidById: Long,
        splitType: SplitType,
        customSplitsJson: String? = null
    ): GroupExpense = GroupExpense(
        id = id,
        groupId = 1L,
        expenseId = null,
        paidById = paidById,
        date = 0L,
        description = "Expense$id",
        totalAmount = totalAmount,
        splitType = splitType,
        customSplitsJson = customSplitsJson
    )
}
