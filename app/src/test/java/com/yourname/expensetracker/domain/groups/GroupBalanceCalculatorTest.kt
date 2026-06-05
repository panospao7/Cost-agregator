package com.yourname.expensetracker.domain.groups

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.dao.GroupSettlementDao
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.GroupSettlementEntity
import com.yourname.expensetracker.data.database.entity.SplitType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class GroupBalanceCalculatorTest {

    private val groupDao = mockk<ExpenseGroupDao>(relaxed = true)
    private val memberDao = mockk<GroupMemberDao>(relaxed = true)
    private val expenseDao = mockk<GroupExpenseDao>(relaxed = true)
    private val settlementDao = mockk<GroupSettlementDao>(relaxed = true)

    private lateinit var calculator: GroupBalanceCalculator

    @Before
    fun setup() {
        calculator = GroupBalanceCalculator(groupDao, memberDao, expenseDao, settlementDao)
    }

    @Test
    fun `new member added later does not change old equal expense share`() = runTest {
        val groupId = 1L
        coEvery { groupDao.getGroupById(groupId) } returns ExpenseGroup(id = groupId, name = "Test Group", defaultCurrency = "EUR")

        val alice = GroupMember(id = 10L, groupId = groupId, name = "Alice", joinedAt = 1_000L)
        val bob = GroupMember(id = 20L, groupId = groupId, name = "Bob", joinedAt = 1_000L)
        val charlie = GroupMember(id = 30L, groupId = groupId, name = "Charlie", joinedAt = 5_000L) // joined later
        coEvery { memberDao.getActiveMembersForGroup(groupId) } returns listOf(alice, bob, charlie)

        // Old expense before Charlie joined
        val oldExpense = GroupExpense(
            id = 100L, groupId = groupId, expenseId = null, paidById = 10L,
            date = 2_000L, description = "Old dinner", totalAmount = 100.0,
            splitType = SplitType.EQUAL, currency = "EUR"
        )
        coEvery { expenseDao.getExpensesForGroupOnce(groupId) } returns listOf(oldExpense)
        coEvery { settlementDao.getSettlementsForGroup(groupId) } returns emptyList()

        val balance = calculator.calculateMemberBalance(groupId, 10L) // Alice paid

        // Alice paid 100, old expense split between 2 members (Alice + Bob) = 50 each
        // Alice owes 50, paid 100 → net = 100 - 50 = +50 (Alice is owed 50)
        assertThat(balance.paidTotal).isEqualTo(100.0)
        assertThat(balance.owedShareTotal).isEqualTo(50.0)
        assertThat(balance.netBalance).isWithin(0.01).of(50.0)
    }

    @Test
    fun `member balance uses joinedAt participation`() = runTest {
        val groupId = 2L
        coEvery { groupDao.getGroupById(groupId) } returns ExpenseGroup(id = groupId, name = "Test Group", defaultCurrency = "EUR")

        val alice = GroupMember(id = 11L, groupId = groupId, name = "Alice", joinedAt = 1_000L)
        val bob = GroupMember(id = 21L, groupId = groupId, name = "Bob", joinedAt = 3_000L) // joined after expense
        coEvery { memberDao.getActiveMembersForGroup(groupId) } returns listOf(alice, bob)

        val expense = GroupExpense(
            id = 101L, groupId = groupId, expenseId = null, paidById = 11L,
            date = 2_000L, description = "Dinner", totalAmount = 60.0,
            splitType = SplitType.EQUAL, currency = "EUR"
        )
        coEvery { expenseDao.getExpensesForGroupOnce(groupId) } returns listOf(expense)
        coEvery { settlementDao.getSettlementsForGroup(groupId) } returns emptyList()

        // Bob joined after expense → Bob owes 0 for this expense
        val bobBalance = calculator.calculateMemberBalance(groupId, 21L)
        assertThat(bobBalance.owedShareTotal).isEqualTo(0.0)
        assertThat(bobBalance.netBalance).isWithin(0.01).of(0.0)

        // Alice paid 60, owes 60 (only participant) → net = 0
        val aliceBalance = calculator.calculateMemberBalance(groupId, 11L)
        assertThat(aliceBalance.owedShareTotal).isEqualTo(60.0)
        assertThat(aliceBalance.netBalance).isWithin(0.01).of(0.0)
    }

    @Test
    fun `cancelled settlement does not affect balance`() = runTest {
        val groupId = 3L
        coEvery { groupDao.getGroupById(groupId) } returns ExpenseGroup(id = groupId, name = "Test Group", defaultCurrency = "EUR")
        coEvery { memberDao.getActiveMembersForGroup(groupId) } returns emptyList()
        coEvery { expenseDao.getExpensesForGroupOnce(groupId) } returns emptyList()

        val cancelledSettlement = GroupSettlementEntity(
            id = 1L, groupId = groupId, fromMemberId = 10L, toMemberId = 20L,
            amount = 30.0, currency = "EUR", createdAt = 1_000L, status = "CANCELLED"
        )
        coEvery { settlementDao.getSettlementsForGroup(groupId) } returns listOf(cancelledSettlement)

        val balance = calculator.calculateMemberBalance(groupId, 10L)
        assertThat(balance.settlementsPaid).isEqualTo(0.0)
        assertThat(balance.netBalance).isWithin(0.01).of(0.0)
    }

    @Test
    fun `foreign currency settlement is ignored`() = runTest {
        val groupId = 4L
        coEvery { groupDao.getGroupById(groupId) } returns ExpenseGroup(id = groupId, name = "Test Group", defaultCurrency = "EUR")
        coEvery { memberDao.getActiveMembersForGroup(groupId) } returns emptyList()
        coEvery { expenseDao.getExpensesForGroupOnce(groupId) } returns emptyList()

        val usdSettlement = GroupSettlementEntity(
            id = 2L, groupId = groupId, fromMemberId = 10L, toMemberId = 20L,
            amount = 50.0, currency = "USD", createdAt = 1_000L, status = "RECORDED"
        )
        coEvery { settlementDao.getSettlementsForGroup(groupId) } returns listOf(usdSettlement)

        val balance = calculator.calculateMemberBalance(groupId, 10L)
        assertThat(balance.settlementsPaid).isEqualTo(0.0)
        assertThat(balance.netBalance).isWithin(0.01).of(0.0)
    }

    @Test
    fun `valid settlement still updates balance`() = runTest {
        val groupId = 5L
        coEvery { groupDao.getGroupById(groupId) } returns ExpenseGroup(id = groupId, name = "Test Group", defaultCurrency = "EUR")
        coEvery { memberDao.getActiveMembersForGroup(groupId) } returns emptyList()
        coEvery { expenseDao.getExpensesForGroupOnce(groupId) } returns emptyList()

        val validSettlement = GroupSettlementEntity(
            id = 3L, groupId = groupId, fromMemberId = 10L, toMemberId = 20L,
            amount = 25.0, currency = "EUR", createdAt = 1_000L, status = "RECORDED"
        )
        coEvery { settlementDao.getSettlementsForGroup(groupId) } returns listOf(validSettlement)

        val fromBalance = calculator.calculateMemberBalance(groupId, 10L)
        assertThat(fromBalance.settlementsPaid).isEqualTo(25.0)

        val toBalance = calculator.calculateMemberBalance(groupId, 20L)
        assertThat(toBalance.settlementsReceived).isEqualTo(25.0)
    }
}
