package com.yourname.expensetracker.verification

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.domain.groups.GroupSplitType
import com.yourname.expensetracker.domain.groups.MemberBalance
import com.yourname.expensetracker.domain.groups.RemoveSharedExpenseMemberResult
import com.yourname.expensetracker.domain.groups.SettlementCalculator
import com.yourname.expensetracker.domain.groups.SharedExpenseDataPort
import com.yourname.expensetracker.domain.groups.SharedExpenseGroup
import com.yourname.expensetracker.domain.groups.SharedExpenseMember
import com.yourname.expensetracker.domain.groups.SharedExpenseManager
import com.yourname.expensetracker.domain.groups.SharedGroupExpense
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SharedExpenseTest {

    private val sharedExpenseDataPort = mockk<SharedExpenseDataPort>(relaxed = true)
    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = 1000L }

    private lateinit var manager: SharedExpenseManager
    private lateinit var settlementCalculator: SettlementCalculator

    @Before
    fun setUp() {
        coEvery { sharedExpenseDataPort.getGroupOnce(any()) } returns null
        manager = SharedExpenseManager(sharedExpenseDataPort, timeProvider, mockk(), ioDispatcher = Dispatchers.Unconfined)
        settlementCalculator = SettlementCalculator(mockk())
    }

    @Test
    fun `equal split divides evenly`() = kotlinx.coroutines.test.runTest {
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns members3
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(1L) } returns listOf(
            expense(total = 90.0, paidBy = 1L, splitType = GroupSplitType.EQUAL)
        )

        val balances = manager.calculateBalances(1L)

        assertApproxEquals(30.0, balances.getValue(1L).shouldPay, 0.0001)
        assertApproxEquals(30.0, balances.getValue(2L).shouldPay, 0.0001)
        assertApproxEquals(30.0, balances.getValue(3L).shouldPay, 0.0001)
    }

    @Test
    fun `percentage split respects ratios`() = kotlinx.coroutines.test.runTest {
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns members3
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(1L) } returns listOf(
            expense(
                total = 100.0,
                paidBy = 1L,
                splitType = GroupSplitType.CUSTOM_PERCENT,
                customSplits = "1:50,2:30,3:20"
            )
        )

        val balances = manager.calculateBalances(1L)

        assertApproxEquals(50.0, balances.getValue(1L).shouldPay, 0.0001)
        assertApproxEquals(30.0, balances.getValue(2L).shouldPay, 0.0001)
        assertApproxEquals(20.0, balances.getValue(3L).shouldPay, 0.0001)
    }

    @Test
    fun `custom split uses exact amounts`() = kotlinx.coroutines.test.runTest {
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns members3
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(1L) } returns listOf(
            expense(
                total = 120.0,
                paidBy = 2L,
                splitType = GroupSplitType.CUSTOM_AMOUNT,
                customSplits = "1:20,2:50,3:50"
            )
        )

        val balances = manager.calculateBalances(1L)

        assertApproxEquals(20.0, balances.getValue(1L).shouldPay, 0.0001)
        assertApproxEquals(50.0, balances.getValue(2L).shouldPay, 0.0001)
        assertApproxEquals(50.0, balances.getValue(3L).shouldPay, 0.0001)
    }

    @Test
    fun `settlement optimization minimizes transactions`() = kotlinx.coroutines.test.runTest {
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns members3
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(1L) } returns listOf(
            // member 1 paid all 90, equal split => two debtors each owe 30
            expense(total = 90.0, paidBy = 1L, splitType = GroupSplitType.EQUAL)
        )

        val balances = manager.calculateBalances(1L)
        val settlements = settlementCalculator.calculateSettlements(balances)

        assertEquals(2, settlements.size)
        assertApproxEquals(60.0, settlements.sumOf { it.amount }, 0.0001)
        assertTrue(settlements.all { it.toMemberId == 1L })
    }

    @Test
    fun `settlement solver finds exact global minimum transfers`() {
        val balances = mapOf(
            1L to MemberBalance(1L, "A", paid = 0.0, shouldPay = 0.0, netBalance = -6.0, currency = "EUR"),
            2L to MemberBalance(2L, "B", paid = 0.0, shouldPay = 0.0, netBalance = -5.0, currency = "EUR"),
            3L to MemberBalance(3L, "C", paid = 0.0, shouldPay = 0.0, netBalance = 1.0, currency = "EUR"),
            4L to MemberBalance(4L, "D", paid = 0.0, shouldPay = 0.0, netBalance = 5.0, currency = "EUR"),
            5L to MemberBalance(5L, "E", paid = 0.0, shouldPay = 0.0, netBalance = 5.0, currency = "EUR")
        )

        val settlements = settlementCalculator.calculateSettlements(balances)

        assertEquals(3, settlements.size)
        assertApproxEquals(11.0, settlements.sumOf { it.amount }, 0.0001)
    }

    @Test
    fun `group balance tracks paid vs owed`() = kotlinx.coroutines.test.runTest {
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns members3
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(1L) } returns listOf(
            expense(total = 90.0, paidBy = 1L, splitType = GroupSplitType.EQUAL),
            expense(total = 30.0, paidBy = 2L, splitType = GroupSplitType.EQUAL)
        )

        val balances = manager.calculateBalances(1L)

        // Totals: each should pay 40. Paid: m1=90,m2=30,m3=0
        assertApproxEquals(50.0, balances.getValue(1L).netBalance, 0.0001)   // owed
        assertApproxEquals(-10.0, balances.getValue(2L).netBalance, 0.0001)  // owes
        assertApproxEquals(-40.0, balances.getValue(3L).netBalance, 0.0001)  // owes
    }

    @Test
    fun `empty group returns zero balances`() = kotlinx.coroutines.test.runTest {
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns emptyList()
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(1L) } returns emptyList()

        val balances = manager.calculateBalances(1L)

        assertTrue(balances.isEmpty())
    }

    @Test
    fun `shared_expense_single_member_equal_split_no_debt`() = kotlinx.coroutines.test.runTest {
        val soloMember = listOf(SharedExpenseMember(id = 1L, groupId = 1L, name = "Solo"))
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns soloMember
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(1L) } returns listOf(
            expense(total = 100.0, paidBy = 1L, splitType = GroupSplitType.EQUAL)
        )

        val balances = manager.calculateBalances(1L)
        val settlements = settlementCalculator.calculateSettlements(balances)

        assertApproxEquals(100.0, balances.getValue(1L).paid, 0.0001)
        assertApproxEquals(100.0, balances.getValue(1L).shouldPay, 0.0001)
        assertApproxEquals(0.0, balances.getValue(1L).netBalance, 0.0001)
        assertTrue(settlements.isEmpty())
    }

    @Test
    fun `shared_expense_large_group_settlement_min_txn_invariant`() = kotlinx.coroutines.test.runTest {
        val members10 = (1L..10L).map { id -> SharedExpenseMember(id = id, groupId = 1L, name = "M$id") }

        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns members10
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(1L) } returns listOf(
            expense(total = 1000.0, paidBy = 1L, splitType = GroupSplitType.EQUAL)
        )

        val balances = manager.calculateBalances(1L)
        val settlements = settlementCalculator.calculateSettlements(balances)

        // 10-way equal split: each should pay 100, payer paid 1000 => 9 debtors owe payer 100 each.
        assertEquals(9, settlements.size)
        assertApproxEquals(900.0, settlements.sumOf { it.amount }, 0.0001)
        assertTrue(settlements.all { it.toMemberId == 1L })
        assertTrue(settlements.all { it.amount > 0.0 })

        val netSum = balances.values.sumOf { it.netBalance }
        assertApproxEquals(0.0, netSum, 0.0001)

        // Conservation: total owed by debtors equals total owed to creditors
        val totalOwedByDebtors = balances.values.filter { it.netBalance < 0 }.sumOf { -it.netBalance }
        val totalOwedToCreditors = balances.values.filter { it.netBalance > 0 }.sumOf { it.netBalance }
        assertApproxEquals(totalOwedByDebtors, totalOwedToCreditors, 0.0001)
        assertApproxEquals(totalOwedByDebtors, settlements.sumOf { it.amount }, 0.0001)
    }

    @Test
    fun `addExpense uses group currency from data port when available`() = kotlinx.coroutines.test.runTest {
        coEvery { sharedExpenseDataPort.getGroupOnce(1L) } returns SharedExpenseGroup(
            id = 1L,
            name = "Trip",
            defaultCurrency = "USD"
        )
        coEvery { sharedExpenseDataPort.addExpense(any()) } answers { firstArg<SharedGroupExpense>().id }

        manager.addExpense(
            groupId = 1L,
            expenseId = 101L,
            paidById = 1L,
            description = "Dinner",
            totalAmount = 42.0,
            currency = "EUR",
            splitType = GroupSplitType.EQUAL,
            customSplits = null
        )

        coVerify(exactly = 1) {
            sharedExpenseDataPort.addExpense(match { it.currency == "USD" })
        }
    }

    @Test
    fun `removeMember blocks deletion when member paid existing expenses`() = kotlinx.coroutines.test.runTest {
        val memberToDelete = members3[1]
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns members3
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(1L) } returns listOf(
            expense(total = 30.0, paidBy = memberToDelete.id, splitType = GroupSplitType.EQUAL)
        )

        val result = manager.removeMember(memberToDelete)

        assertTrue(result is RemoveSharedExpenseMemberResult.CannotDeleteMemberWithExpenses)
        result as RemoveSharedExpenseMemberResult.CannotDeleteMemberWithExpenses
        assertEquals(1, result.expenseCount)
        coVerify(exactly = 0) { sharedExpenseDataPort.removeMember(any()) }
    }

    @Test
    fun `removeMember blocks deletion when custom split references member`() = kotlinx.coroutines.test.runTest {
        val memberToDelete = members3[1]
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns members3
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(1L) } returns listOf(
            expense(
                total = 30.0,
                paidBy = members3[0].id,
                splitType = GroupSplitType.CUSTOM_AMOUNT,
                customSplits = "1:15,2:10,3:5"
            )
        )

        val result = manager.removeMember(memberToDelete)

        assertTrue(result is RemoveSharedExpenseMemberResult.CannotDeleteMemberReferencedInSplits)
        result as RemoveSharedExpenseMemberResult.CannotDeleteMemberReferencedInSplits
        assertEquals(1, result.expenseCount)
        coVerify(exactly = 0) { sharedExpenseDataPort.removeMember(any()) }
    }

    @Test
    fun `removeMember blocks deletion when equal split expense is on or after joinedAt`() = kotlinx.coroutines.test.runTest {
        val joinedAt = 1_700_000_000_000L
        val members = listOf(
            SharedExpenseMember(id = 1L, groupId = 1L, name = "Alice", joinedAt = joinedAt - 10_000L),
            SharedExpenseMember(id = 2L, groupId = 1L, name = "Bob", joinedAt = joinedAt),
            SharedExpenseMember(id = 3L, groupId = 1L, name = "Carol", joinedAt = joinedAt - 10_000L)
        )
        val memberToDelete = members[1]
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns members
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(1L) } returns listOf(
            expense(total = 30.0, paidBy = members[0].id, splitType = GroupSplitType.EQUAL, date = joinedAt)
        )

        val result = manager.removeMember(memberToDelete)

        assertTrue(result is RemoveSharedExpenseMemberResult.CannotDeleteMemberReferencedInSplits)
        result as RemoveSharedExpenseMemberResult.CannotDeleteMemberReferencedInSplits
        assertEquals(1, result.expenseCount)
        coVerify(exactly = 0) { sharedExpenseDataPort.removeMember(any()) }
    }

    @Test
    fun `removeMember allows deletion when equal split expense is before joinedAt`() = kotlinx.coroutines.test.runTest {
        val joinedAt = 1_700_000_000_000L
        val members = listOf(
            SharedExpenseMember(id = 1L, groupId = 1L, name = "Alice", joinedAt = joinedAt - 10_000L),
            SharedExpenseMember(id = 2L, groupId = 1L, name = "Bob", joinedAt = joinedAt),
            SharedExpenseMember(id = 3L, groupId = 1L, name = "Carol", joinedAt = joinedAt - 10_000L)
        )
        val memberToDelete = members[1]
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns members
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(1L) } returns listOf(
            expense(total = 30.0, paidBy = members[0].id, splitType = GroupSplitType.EQUAL, date = joinedAt - 1L)
        )

        val result = manager.removeMember(memberToDelete)

        assertTrue(result is RemoveSharedExpenseMemberResult.Success)
        coVerify(exactly = 1) { sharedExpenseDataPort.removeMember(memberToDelete) }
    }

    @Test
    fun `addExpense rejects non-finite custom split values before persistence`() = kotlinx.coroutines.test.runTest {
        try {
            manager.addExpense(
                groupId = 1L,
                expenseId = 101L,
                paidById = 1L,
                description = "Dinner",
                totalAmount = 42.0,
                currency = "EUR",
                splitType = GroupSplitType.CUSTOM_AMOUNT,
                customSplits = mapOf(1L to Double.NaN, 2L to 42.0)
            )
            fail("Expected IllegalArgumentException for non-finite custom split values")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("must be finite") == true)
        }

        coVerify(exactly = 0) { sharedExpenseDataPort.addExpense(any()) }
    }

    private fun expense(
        total: Double,
        paidBy: Long,
        splitType: GroupSplitType,
        customSplits: String? = null,
        date: Long = 1_700_000_000_000
    ) = SharedGroupExpense(
        id = 1L,
        groupId = 1L,
        expenseId = null,
        paidById = paidBy,
        date = date,
        description = "test",
        totalAmount = total,
        currency = "EUR",
        splitType = splitType,
        customSplitsSerialized = customSplits,
    )

    private val members3 = listOf(
        SharedExpenseMember(id = 1L, groupId = 1L, name = "Alice"),
        SharedExpenseMember(id = 2L, groupId = 1L, name = "Bob"),
        SharedExpenseMember(id = 3L, groupId = 1L, name = "Carol")
    )
}