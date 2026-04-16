package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `large equal split stays positive and preserves total`() {
        val members = members(1L, 2L)
        val expense = groupExpense(totalAmount = 25_000_000.00, paidById = 1L, splitType = SplitType.EQUAL)

        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertApproxEquals(12_500_000.00, splits[1L] ?: 0.0, 0.01)
        assertApproxEquals(12_500_000.00, splits[2L] ?: 0.0, 0.01)
        assertApproxEquals(25_000_000.00, splits.values.sum(), 0.001)
        assertApproxEquals(12_500_000.00, SplitCalculator.calculateMemberShare(expense, members, 1L), 0.01)
        assertTrue(SplitCalculator.validateSplits(splits, 25_000_000.00))
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
    fun `equal split validation rejects backdated expense when payer joined after expense date`() {
        val expenseDate = 1_000L
        val members = listOf(
            member(id = 1L, joinedAt = expenseDate + 1L),
            member(id = 2L, joinedAt = expenseDate - 1L)
        )
        val expense = groupExpense(
            totalAmount = 100.00,
            paidById = 1L,
            splitType = SplitType.EQUAL,
            date = expenseDate
        )

        val validationError = SplitCalculator.validateExpenseParticipants(expense, members)
        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertEquals(
            "Equal splits require the payer to have joined on or before the expense date",
            validationError
        )
        assertTrue(splits.isEmpty())
        assertApproxEquals(0.0, SplitCalculator.calculateMemberShare(expense, members, 1L), 0.001)
    }

    @Test
    fun `equal split validation rejects backdated expense when no participant qualifies`() {
        val expenseDate = 1_000L
        val members = listOf(
            member(id = 1L, joinedAt = expenseDate + 1L),
            member(id = 2L, joinedAt = expenseDate + 2L)
        )
        val expense = groupExpense(
            totalAmount = 100.00,
            paidById = 1L,
            splitType = SplitType.EQUAL,
            date = expenseDate
        )

        val validationError = SplitCalculator.validateExpenseParticipants(expense, members)
        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertEquals(
            "Equal splits require at least one participant who joined on or before the expense date",
            validationError
        )
        assertTrue(splits.isEmpty())
        assertTrue(SplitCalculator.getSplitParticipants(expense, members).isEmpty())
    }

    @Test
    fun `equal split validation allows backdated expense when payer is still an eligible participant`() {
        val expenseDate = 1_000L
        val members = listOf(
            member(id = 1L, joinedAt = expenseDate),
            member(id = 2L, joinedAt = expenseDate - 50L),
            member(id = 3L, joinedAt = expenseDate + 50L)
        )
        val expense = groupExpense(
            totalAmount = 90.00,
            paidById = 1L,
            splitType = SplitType.EQUAL,
            date = expenseDate
        )

        val validationError = SplitCalculator.validateExpenseParticipants(expense, members)
        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertNull(validationError)
        assertEquals(setOf(1L, 2L), splits.keys)
        assertApproxEquals(45.0, splits[1L] ?: 0.0, 0.01)
        assertApproxEquals(45.0, splits[2L] ?: 0.0, 0.01)
        assertApproxEquals(90.0, splits.values.sum(), 0.001)
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
            member(
                id = id,
                joinedAt = 0L,
                isCurrentUser = id == 1L
            )
        }

    private fun member(
        id: Long,
        joinedAt: Long,
        isCurrentUser: Boolean = id == 1L
    ): GroupMember = GroupMember(
        id = id,
        groupId = 1L,
        name = "Member$id",
        isCurrentUser = isCurrentUser,
        joinedAt = joinedAt
    )

    private fun groupExpense(
        id: Long = 1L,
        totalAmount: Double,
        paidById: Long,
        splitType: SplitType,
        customSplitsJson: String? = null,
        date: Long = 0L
    ): GroupExpense = GroupExpense(
        id = id,
        groupId = 1L,
        expenseId = null,
        paidById = paidById,
        date = date,
        description = "Expense$id",
        totalAmount = totalAmount,
        splitType = splitType,
        customSplitsJson = customSplitsJson
    )
}
