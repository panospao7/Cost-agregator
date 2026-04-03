package com.yourname.expensetracker.verification

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.domain.groups.GroupTransactionCoordinator
import com.yourname.expensetracker.domain.groups.SettlementCalculator
import com.yourname.expensetracker.domain.groups.SharedExpenseManager
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SharedExpenseTest {

    private val groupDao = mockk<ExpenseGroupDao>(relaxed = true)
    private val memberDao = mockk<GroupMemberDao>(relaxed = true)
    private val groupExpenseDao = mockk<GroupExpenseDao>(relaxed = true)
    private val txCoordinator = mockk<GroupTransactionCoordinator>(relaxed = true)

    private lateinit var manager: SharedExpenseManager
    private lateinit var settlementCalculator: SettlementCalculator

    @Before
    fun setUp() {
        manager = SharedExpenseManager(groupDao, memberDao, groupExpenseDao, txCoordinator)
        settlementCalculator = SettlementCalculator()
    }

    @Test
    fun `equal split divides evenly`() = kotlinx.coroutines.test.runTest {
        coEvery { memberDao.getMembersForGroupOnce(1L) } returns members3
        coEvery { groupExpenseDao.getExpensesForGroupOnce(1L) } returns listOf(
            expense(total = 90.0, paidBy = 1L, splitType = SplitType.EQUAL)
        )

        val balances = manager.calculateBalances(1L)

        assertApproxEquals(30.0, balances.getValue(1L).shouldPay, 0.0001)
        assertApproxEquals(30.0, balances.getValue(2L).shouldPay, 0.0001)
        assertApproxEquals(30.0, balances.getValue(3L).shouldPay, 0.0001)
    }

    @Test
    fun `percentage split respects ratios`() = kotlinx.coroutines.test.runTest {
        coEvery { memberDao.getMembersForGroupOnce(1L) } returns members3
        coEvery { groupExpenseDao.getExpensesForGroupOnce(1L) } returns listOf(
            expense(
                total = 100.0,
                paidBy = 1L,
                splitType = SplitType.CUSTOM_PERCENT,
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
        coEvery { memberDao.getMembersForGroupOnce(1L) } returns members3
        coEvery { groupExpenseDao.getExpensesForGroupOnce(1L) } returns listOf(
            expense(
                total = 120.0,
                paidBy = 2L,
                splitType = SplitType.CUSTOM_AMOUNT,
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
        coEvery { memberDao.getMembersForGroupOnce(1L) } returns members3
        coEvery { groupExpenseDao.getExpensesForGroupOnce(1L) } returns listOf(
            // member 1 paid all 90, equal split => two debtors each owe 30
            expense(total = 90.0, paidBy = 1L, splitType = SplitType.EQUAL)
        )

        val balances = manager.calculateBalances(1L)
        val settlements = settlementCalculator.calculateSettlements(balances)

        assertEquals(2, settlements.size)
        assertApproxEquals(60.0, settlements.sumOf { it.amount }, 0.0001)
        assertTrue(settlements.all { it.toMemberId == 1L })
    }

    @Test
    fun `group balance tracks paid vs owed`() = kotlinx.coroutines.test.runTest {
        coEvery { memberDao.getMembersForGroupOnce(1L) } returns members3
        coEvery { groupExpenseDao.getExpensesForGroupOnce(1L) } returns listOf(
            expense(total = 90.0, paidBy = 1L, splitType = SplitType.EQUAL),
            expense(total = 30.0, paidBy = 2L, splitType = SplitType.EQUAL)
        )

        val balances = manager.calculateBalances(1L)

        // Totals: each should pay 40. Paid: m1=90,m2=30,m3=0
        assertApproxEquals(50.0, balances.getValue(1L).netBalance, 0.0001)   // owed
        assertApproxEquals(-10.0, balances.getValue(2L).netBalance, 0.0001)  // owes
        assertApproxEquals(-40.0, balances.getValue(3L).netBalance, 0.0001)  // owes
    }

    @Test
    fun `empty group returns zero balances`() = kotlinx.coroutines.test.runTest {
        coEvery { memberDao.getMembersForGroupOnce(1L) } returns emptyList()
        coEvery { groupExpenseDao.getExpensesForGroupOnce(1L) } returns emptyList()

        val balances = manager.calculateBalances(1L)

        assertTrue(balances.isEmpty())
    }

    private fun expense(
        total: Double,
        paidBy: Long,
        splitType: SplitType,
        customSplits: String? = null
    ) = GroupExpense(
        id = 1L,
        groupId = 1L,
        expenseId = null,
        paidById = paidBy,
        date = 1_700_000_000_000,
        description = "test",
        totalAmount = total,
        splitType = splitType,
        customSplitsJson = customSplits
    )

    private val members3 = listOf(
        GroupMember(id = 1L, groupId = 1L, name = "Alice"),
        GroupMember(id = 2L, groupId = 1L, name = "Bob"),
        GroupMember(id = 3L, groupId = 1L, name = "Carol")
    )
}
