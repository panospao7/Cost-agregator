package com.yourname.expensetracker.e2e

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.domain.groups.GroupSplitType
import com.yourname.expensetracker.domain.groups.MemberBalance
import com.yourname.expensetracker.domain.groups.SettlementCalculator
import com.yourname.expensetracker.domain.groups.SharedExpenseDataPort
import com.yourname.expensetracker.domain.groups.SharedExpenseManager
import com.yourname.expensetracker.domain.groups.SharedExpenseMember
import com.yourname.expensetracker.domain.groups.SharedGroupExpense
import com.yourname.expensetracker.domain.logic.SplitCalculator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GroupSettlementPipelineTest : AnalyticsEngineTestBase() {

    private val splitCalculator = SplitCalculator
    private val settlementCalculator = SettlementCalculator(mockk())
    private lateinit var sharedExpenseManager: SharedExpenseManager
    private val dataPort = mockk<SharedExpenseDataPort>(relaxed = true)

    @Before
    override fun setUp() {
        super.setUp()
        sharedExpenseManager = SharedExpenseManager(dataPort, timeProvider, testDispatcher, ioDispatcher = Dispatchers.Unconfined)
    }

    @Test
    fun `4-member equal split preserves sum and yields zero-sum balances`() = runTest {
        val groupId = 1L
        val members = listOf(
            member(1L, "Alice"),
            member(2L, "Bob"),
            member(3L, "Charlie"),
            member(4L, "Diana")
        )
        val expense = groupExpense(totalAmount = 100.0, splitType = SplitType.EQUAL)

        val splits = splitCalculator.calculateSplitAmounts(expense, members)

        assertApproxEquals(100.0, splits.values.sum(), 0.001)

        coEvery { dataPort.getGroupMembersOnce(groupId) } returns members.map {
            sharedMember(groupId = groupId, memberId = it.id, name = it.name)
        }
        coEvery { dataPort.getGroupExpensesOnce(groupId) } returns listOf(
            sharedExpense(
                groupId = groupId,
                paidById = 1L,
                totalAmount = 100.0,
                splitType = GroupSplitType.EQUAL
            )
        )

        val balances = sharedExpenseManager.calculateBalances(groupId)

        assertApproxEquals(0.0, balances.values.sumOf { it.netBalance }, 0.01)
    }

    @Test
    fun `percentage split distributes according to configured ratios`() {
        val members = listOf(
            member(1L, "Alice"),
            member(2L, "Bob"),
            member(3L, "Charlie"),
            member(4L, "Diana")
        )
        val expense = groupExpense(
            totalAmount = 100.0,
            splitType = SplitType.CUSTOM_PERCENT,
            customSplitsJson = "1:50,2:20,3:20,4:10"
        )

        val splits = splitCalculator.calculateSplitAmounts(expense, members)

        assertApproxEquals(50.0, splits[1L] ?: 0.0, 0.01)
        assertApproxEquals(20.0, splits[2L] ?: 0.0, 0.01)
        assertApproxEquals(20.0, splits[3L] ?: 0.0, 0.01)
        assertApproxEquals(10.0, splits[4L] ?: 0.0, 0.01)
        assertApproxEquals(100.0, splits.values.sum(), 0.001)
    }

    @Test
    fun `settlement calculator produces minimal transfer count for mixed balances`() {
        val balances = mapOf(
            1L to memberBalance(1L, "Alice", 3.0),
            2L to memberBalance(2L, "Bob", 6.0),
            3L to memberBalance(3L, "Charlie", -4.0),
            4L to memberBalance(4L, "Diana", -5.0)
        )

        val settlements = settlementCalculator.calculateSettlements(balances)

        assertEquals(3, settlements.size)
        assertApproxEquals(9.0, settlements.sumOf { it.amount }, 0.01)
        assertTrue(settlements.all { it.amount > 0.0 })
        assertTrue(settlements.none { it.usedGreedyFallback })
    }

    @Test
    fun `all-zero balances produce empty settlement plan`() {
        val balances = mapOf(
            1L to memberBalance(1L, "Alice", 0.0),
            2L to memberBalance(2L, "Bob", 0.0),
            3L to memberBalance(3L, "Charlie", 0.0),
            4L to memberBalance(4L, "Diana", 0.0)
        )

        val settlements = settlementCalculator.calculateSettlements(balances)

        assertTrue(settlements.isEmpty())
        assertApproxEquals(0.0, settlementCalculator.getTotalSettlementAmount(settlements), 0.0)
    }

    private fun member(id: Long, name: String): GroupMember {
        return GroupMember(
            id = id,
            groupId = 1L,
            name = name,
            isCurrentUser = id == 1L,
            joinedAt = 0L
        )
    }

    private fun groupExpense(
        totalAmount: Double,
        splitType: SplitType,
        paidById: Long = 1L,
        customSplitsJson: String? = null
    ): GroupExpense {
        return GroupExpense(
            id = 1L,
            groupId = 1L,
            expenseId = null,
            paidById = paidById,
            date = 0L,
            description = "E2E Group Expense",
            totalAmount = totalAmount,
            splitType = splitType,
            customSplitsJson = customSplitsJson
        )
    }

    private fun sharedMember(groupId: Long, memberId: Long, name: String): SharedExpenseMember {
        return SharedExpenseMember(
            id = memberId,
            groupId = groupId,
            name = name,
            isCurrentUser = memberId == 1L,
            joinedAt = 0L
        )
    }

    private fun sharedExpense(
        groupId: Long,
        paidById: Long,
        totalAmount: Double,
        splitType: GroupSplitType,
        customSplitsSerialized: String? = null
    ): SharedGroupExpense {
        return SharedGroupExpense(
            id = 1L,
            groupId = groupId,
            expenseId = 101L,
            paidById = paidById,
            date = 0L,
            description = "Shared Expense",
            totalAmount = totalAmount,
            currency = "EUR",
            splitType = splitType,
            customSplitsSerialized = customSplitsSerialized,
        )
    }

    private fun memberBalance(memberId: Long, memberName: String, netBalance: Double): MemberBalance {
        val paid = if (netBalance > 0.0) netBalance else 0.0
        val shouldPay = if (netBalance < 0.0) -netBalance else 0.0
        return MemberBalance(
            memberId = memberId,
            memberName = memberName,
            paid = paid,
            shouldPay = shouldPay,
            netBalance = netBalance,
            currency = "EUR",
        )
    }
}