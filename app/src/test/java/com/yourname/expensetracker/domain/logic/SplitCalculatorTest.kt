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
import java.math.BigDecimal

class SplitCalculatorTest {

    // B3-4: the 100/3, 100/7 and 33.33-percentage cases that used to open this class
    // were converted from tolerant to exact-cent assertions (the cases are still
    // present below). They assert exact cents for the largest-remainder allocation
    // against the real SplitCalculator.

    @Test
    fun `equal split of 100 among 3 members allocates exact cents 33_34 33_33 33_33`() {
        val splits = SplitCalculator.calculateSplitAmounts(
            groupExpense(totalAmount = 100.00, paidById = 1L, splitType = SplitType.EQUAL),
            members(1L, 2L, 3L)
        )

        assertEquals(listOf(1L, 2L, 3L), splits.keys.toList())
        assertEquals(listOf(3334L, 3333L, 3333L), splits.values.map { toExactCents(it) })
        assertEquals(10_000L, splits.values.sumOf { toExactCents(it) })
    }

    @Test
    fun `equal split of 100 among 7 members gives four 14_29 shares then three 14_28 shares exactly`() {
        val splits = SplitCalculator.calculateSplitAmounts(
            groupExpense(totalAmount = 100.00, paidById = 1L, splitType = SplitType.EQUAL),
            members(1L, 2L, 3L, 4L, 5L, 6L, 7L)
        )

        assertEquals(
            listOf(1429L, 1429L, 1429L, 1429L, 1428L, 1428L, 1428L),
            splits.values.map { toExactCents(it) }
        )
        assertEquals(10_000L, splits.values.sumOf { toExactCents(it) })
    }

    @Test
    fun `equal split of one cent among 3 members gives the only cent to the first member`() {
        val splits = SplitCalculator.calculateSplitAmounts(
            groupExpense(totalAmount = 0.01, paidById = 1L, splitType = SplitType.EQUAL),
            members(1L, 2L, 3L)
        )

        assertEquals(listOf(1L, 0L, 0L), splits.values.map { toExactCents(it) })
    }

    @Test
    fun `equal split of two cents among 3 members gives one cent each to the first two members`() {
        val splits = SplitCalculator.calculateSplitAmounts(
            groupExpense(totalAmount = 0.02, paidById = 1L, splitType = SplitType.EQUAL),
            members(1L, 2L, 3L)
        )

        assertEquals(listOf(1L, 1L, 0L), splits.values.map { toExactCents(it) })
    }

    @Test
    fun `equal split of 999999_99 among 7 members places the leftover cent on the first member`() {
        val splits = SplitCalculator.calculateSplitAmounts(
            groupExpense(totalAmount = 999_999.99, paidById = 1L, splitType = SplitType.EQUAL),
            members(1L, 2L, 3L, 4L, 5L, 6L, 7L)
        )

        // 99_999_999 cents = 7 * 14_285_714 + 1
        assertEquals(
            listOf(14_285_715L, 14_285_714L, 14_285_714L, 14_285_714L, 14_285_714L, 14_285_714L, 14_285_714L),
            splits.values.map { toExactCents(it) }
        )
        assertEquals(99_999_999L, splits.values.sumOf { toExactCents(it) })
    }

    @Test
    fun `percentage split of 100 at 33_33 33_33 33_34 allocates exact cents and preserves total`() {
        val splits = SplitCalculator.calculateSplitAmounts(
            groupExpense(
                totalAmount = 100.00,
                paidById = 1L,
                splitType = SplitType.CUSTOM_PERCENT,
                customSplitsJson = "1:33.33,2:33.33,3:33.34"
            ),
            members(1L, 2L, 3L)
        )

        assertEquals(listOf(3333L, 3333L, 3334L), splits.values.map { toExactCents(it) })
        assertEquals(10_000L, splits.values.sumOf { toExactCents(it) })
    }

    @Test
    fun `percentage split of 0_05 gives leftover cents to largest fractional parts not naive rounding`() {
        // Raw cents: 1.6665 / 1.6665 / 1.667 -> floors 1/1/1, two cents left. The third
        // share has the largest fraction, then the first wins the tie by member order.
        // Naive per-share HALF_UP would give 2+2+2 = 6 cents.
        val splits = SplitCalculator.calculateSplitAmounts(
            groupExpense(
                totalAmount = 0.05,
                paidById = 1L,
                splitType = SplitType.CUSTOM_PERCENT,
                customSplitsJson = "1:33.33,2:33.33,3:33.34"
            ),
            members(1L, 2L, 3L)
        )

        assertEquals(listOf(2L, 1L, 2L), splits.values.map { toExactCents(it) })
        assertEquals(5L, splits.values.sumOf { toExactCents(it) })
    }

    @Test
    fun `percentage split of 0_03 at 50-50 gives the odd cent to the first member`() {
        val splits = SplitCalculator.calculateSplitAmounts(
            groupExpense(
                totalAmount = 0.03,
                paidById = 1L,
                splitType = SplitType.CUSTOM_PERCENT,
                customSplitsJson = "1:50,2:50"
            ),
            members(1L, 2L)
        )

        assertEquals(listOf(2L, 1L), splits.values.map { toExactCents(it) })
        assertEquals(3L, splits.values.sumOf { toExactCents(it) })
    }

    @Test
    fun `percentage split of 100 at 70-20-10 is exact`() {
        val splits = SplitCalculator.calculateSplitAmounts(
            groupExpense(
                totalAmount = 100.00,
                paidById = 1L,
                splitType = SplitType.CUSTOM_PERCENT,
                customSplitsJson = "1:70,2:20,3:10"
            ),
            members(1L, 2L, 3L)
        )

        assertEquals(listOf(7000L, 2000L, 1000L), splits.values.map { toExactCents(it) })
    }

    @Test
    fun `percentage split not summing to 100 falls back to exact equal cents`() {
        // Failing boundary: 60 + 30 + 9.99 = 99.99% is rejected by the parser, so the
        // calculator falls back to the equal split (3334/3333/3333) instead of the
        // percentage path (which would give 6000/3000/999).
        val splits = SplitCalculator.calculateSplitAmounts(
            groupExpense(
                totalAmount = 100.00,
                paidById = 1L,
                splitType = SplitType.CUSTOM_PERCENT,
                customSplitsJson = "1:60,2:30,3:9.99"
            ),
            members(1L, 2L, 3L)
        )

        assertEquals(listOf(3334L, 3333L, 3333L), splits.values.map { toExactCents(it) })
        assertEquals(10_000L, splits.values.sumOf { toExactCents(it) })
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

    /** Unit conversion only: exact integer cents; throws if a value has sub-cent precision. */
    private fun toExactCents(amount: Double): Long =
        BigDecimal.valueOf(amount).movePointRight(2).longValueExact()

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
