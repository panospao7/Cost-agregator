package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import org.junit.Test

class SplitCalculatorGoldenTest {

    @Test
    fun `equal split of 100 among 3 members distributes remainder to first member`() {
        val members = members(3)
        val expense = groupExpense(totalAmount = 100.0, splitType = SplitType.EQUAL)

        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertApproxEquals(33.34, splits.getValue(1L), 0.01)
        assertApproxEquals(33.33, splits.getValue(2L), 0.01)
        assertApproxEquals(33.33, splits.getValue(3L), 0.01)
        assertApproxEquals(100.00, splits.values.sum(), 0.001)
    }

    @Test
    fun `equal split of 100 among 7 members distributes 4 cent remainder to first 4 members`() {
        val members = members(7)
        val expense = groupExpense(totalAmount = 100.0, splitType = SplitType.EQUAL)

        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertApproxEquals(14.29, splits.getValue(1L), 0.01)
        assertApproxEquals(14.29, splits.getValue(2L), 0.01)
        assertApproxEquals(14.29, splits.getValue(3L), 0.01)
        assertApproxEquals(14.29, splits.getValue(4L), 0.01)
        assertApproxEquals(14.28, splits.getValue(5L), 0.01)
        assertApproxEquals(14.28, splits.getValue(6L), 0.01)
        assertApproxEquals(14.28, splits.getValue(7L), 0.01)
        assertApproxEquals(100.00, splits.values.sum(), 0.001)
    }

    @Test
    fun `percentage split 33 33 33 34 produces exact cent-preserving shares`() {
        val members = members(3)
        val expense = groupExpense(
            totalAmount = 100.0,
            splitType = SplitType.CUSTOM_PERCENT,
            customSplitsJson = "1:33.33,2:33.33,3:33.34"
        )

        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertApproxEquals(33.33, splits.getValue(1L), 0.01)
        assertApproxEquals(33.33, splits.getValue(2L), 0.01)
        assertApproxEquals(33.34, splits.getValue(3L), 0.01)
        assertApproxEquals(100.00, splits.values.sum(), 0.001)
    }

    @Test
    fun `percentage split 33 33 33 33 assigns remainder cent to first member by tie-break order`() {
        val members = members(3)
        val expense = groupExpense(
            totalAmount = 100.0,
            splitType = SplitType.CUSTOM_PERCENT,
            customSplitsJson = "1:33.33,2:33.33,3:33.33"
        )

        val splits = SplitCalculator.calculateSplitAmounts(expense, members)

        assertApproxEquals(33.34, splits.getValue(1L), 0.01)
        assertApproxEquals(33.33, splits.getValue(2L), 0.01)
        assertApproxEquals(33.33, splits.getValue(3L), 0.01)
        assertApproxEquals(100.00, splits.values.sum(), 0.001)
    }

    private fun members(count: Int): List<GroupMember> {
        return (1..count).map { index ->
            GroupMember(
                id = index.toLong(),
                groupId = 1L,
                name = "Member $index"
            )
        }
    }

    private fun groupExpense(
        totalAmount: Double,
        splitType: SplitType,
        customSplitsJson: String? = null
    ): GroupExpense {
        return GroupExpense(
            id = 1L,
            groupId = 1L,
            expenseId = null,
            paidById = 1L,
            date = 0L,
            description = "Golden test expense",
            totalAmount = totalAmount,
            splitType = splitType,
            customSplitsJson = customSplitsJson
        )
    }
}
