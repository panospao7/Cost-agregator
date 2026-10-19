package com.yourname.expensetracker.domain.groups

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.assertMoneyEquals
import com.yourname.expensetracker.assertMoneyExact
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
        coEvery { memberDao.getAllForGroup(groupId) } returns listOf(alice, bob, charlie)

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
        coEvery { memberDao.getAllForGroup(groupId) } returns listOf(alice, bob)

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

    /**
     * Calculator wiring of the settlement rules (the rules themselves are owned by
     * GroupSettlementBalancePolicyTest). Replaces: cancelled settlement does not affect balance,
     * foreign currency settlement is ignored, valid settlement still updates balance, and
     * cancelled unknown and foreign repayments remain excluded.
     * Setup: Bob paid a 100 EUR equal expense for Alice + Bob, so the base net is Alice -50 / Bob +50.
     */
    @Test
    fun `settlement status and currency rows reach calculator paid received and net fields`() = runTest {
        data class Row(
            val name: String,
            val status: String,
            val currency: String,
            val amount: Double,
            val expectedPaid: Double,
            val expectedAliceNet: Double,
            val expectedBobNet: Double
        )
        val rows = listOf(
            Row("CANCELLED EUR 30 is excluded", "CANCELLED", "EUR", 30.0, 0.0, -50.0, 50.0),
            Row("UNKNOWN EUR 10 is excluded", "UNKNOWN", "EUR", 10.0, 0.0, -50.0, 50.0),
            Row("RECORDED USD 50 foreign currency is excluded", "RECORDED", "USD", 50.0, 0.0, -50.0, 50.0),
            Row("RECORDED EUR 25 is applied", "RECORDED", "EUR", 25.0, 25.0, -25.0, 25.0)
        )
        assertThat(rows).hasSize(4)

        rows.forEachIndexed { index, row ->
            val groupId = 30L + index
            val alice = GroupMember(id = 10L, groupId = groupId, name = "Alice")
            val bob = GroupMember(id = 20L, groupId = groupId, name = "Bob")
            val expense = GroupExpense(300L + index, groupId, null, bob.id, 1_000L, "Dinner", 100.0, "EUR", SplitType.EQUAL)
            coEvery { groupDao.getGroupById(groupId) } returns ExpenseGroup(id = groupId, name = "Trip", defaultCurrency = "EUR")
            coEvery { memberDao.getAllForGroup(groupId) } returns listOf(alice, bob)
            coEvery { expenseDao.getExpensesForGroupOnce(groupId) } returns listOf(expense)
            coEvery { settlementDao.getSettlementsForGroup(groupId) } returns listOf(
                GroupSettlementEntity(
                    id = 40L + index, groupId = groupId, fromMemberId = alice.id, toMemberId = bob.id,
                    amount = row.amount, currency = row.currency, createdAt = 2_000L, status = row.status
                )
            )

            val aliceBalance = calculator.calculateMemberBalance(groupId, alice.id)
            val bobBalance = calculator.calculateMemberBalance(groupId, bob.id)

            assertMoneyExact(row.expectedPaid, aliceBalance.settlementsPaid, "${row.name}: Alice settlementsPaid")
            assertMoneyExact(row.expectedPaid, bobBalance.settlementsReceived, "${row.name}: Bob settlementsReceived")
            assertMoneyEquals(row.expectedAliceNet, aliceBalance.netBalance, "${row.name}: Alice net")
            assertMoneyEquals(row.expectedBobNet, bobBalance.netBalance, "${row.name}: Bob net")
        }
    }

    /** An ineligible settlement must be skipped (continue), not end processing of later eligible ones. */
    @Test
    fun `ineligible settlements before an eligible one in the same list do not stop later settlements`() = runTest {
        val groupId = 50L
        val alice = GroupMember(id = 10L, groupId = groupId, name = "Alice")
        val bob = GroupMember(id = 20L, groupId = groupId, name = "Bob")
        val expense = GroupExpense(350L, groupId, null, bob.id, 1_000L, "Dinner", 100.0, "EUR", SplitType.EQUAL)
        coEvery { groupDao.getGroupById(groupId) } returns ExpenseGroup(id = groupId, name = "Trip", defaultCurrency = "EUR")
        coEvery { memberDao.getAllForGroup(groupId) } returns listOf(alice, bob)
        coEvery { expenseDao.getExpensesForGroupOnce(groupId) } returns listOf(expense)
        coEvery { settlementDao.getSettlementsForGroup(groupId) } returns listOf(
            GroupSettlementEntity(id = 60L, groupId = groupId, fromMemberId = alice.id, toMemberId = bob.id, amount = 30.0, currency = "EUR", createdAt = 2_000L, status = "CANCELLED"),
            GroupSettlementEntity(id = 61L, groupId = groupId, fromMemberId = alice.id, toMemberId = bob.id, amount = 50.0, currency = "USD", createdAt = 2_500L, status = "RECORDED"),
            GroupSettlementEntity(id = 62L, groupId = groupId, fromMemberId = alice.id, toMemberId = bob.id, amount = 25.0, currency = "EUR", createdAt = 3_000L, status = "RECORDED")
        )

        val aliceBalance = calculator.calculateMemberBalance(groupId, alice.id)
        val bobBalance = calculator.calculateMemberBalance(groupId, bob.id)

        assertMoneyExact(25.0, aliceBalance.settlementsPaid, "Alice settlementsPaid counts only the eligible later settlement")
        assertMoneyExact(25.0, bobBalance.settlementsReceived, "Bob settlementsReceived counts only the eligible later settlement")
        assertMoneyEquals(-25.0, aliceBalance.netBalance, "Alice net")
        assertMoneyEquals(25.0, bobBalance.netBalance, "Bob net")
    }

    @Test
    fun `repayment signs move debtor and creditor toward zero`() = runTest {
        val groupId = 6L
        val alice = GroupMember(id = 10L, groupId = groupId, name = "Alice", joinedAt = 0L)
        val bob = GroupMember(id = 20L, groupId = groupId, name = "Bob", joinedAt = 0L)
        val expense = GroupExpense(
            id = 200L, groupId = groupId, expenseId = null, paidById = bob.id,
            date = 1_000L, description = "Dinner", totalAmount = 100.0,
            splitType = SplitType.EQUAL, currency = "EUR"
        )
        coEvery { groupDao.getGroupById(groupId) } returns ExpenseGroup(id = groupId, name = "Trip", defaultCurrency = "EUR")
        coEvery { memberDao.getAllForGroup(groupId) } returns listOf(alice, bob)
        coEvery { expenseDao.getExpensesForGroupOnce(groupId) } returns listOf(expense)
        coEvery { settlementDao.getSettlementsForGroup(groupId) } returns listOf(
            GroupSettlementEntity(id = 2L, groupId = groupId, fromMemberId = alice.id, toMemberId = bob.id, amount = 20.0, currency = "EUR", createdAt = 2_000L, status = "RECORDED")
        )
        val partialAlice = calculator.calculateMemberBalance(groupId, alice.id)
        val partialBob = calculator.calculateMemberBalance(groupId, bob.id)
        assertThat(partialAlice.netBalance).isWithin(0.01).of(-30.0)
        assertThat(partialBob.netBalance).isWithin(0.01).of(30.0)
        assertThat(partialAlice.netBalance + partialBob.netBalance).isEqualTo(0.0)
        assertThat(partialAlice.isSettled).isFalse()
        assertThat(partialBob.isSettled).isFalse()

        coEvery { settlementDao.getSettlementsForGroup(groupId) } returns listOf(
            GroupSettlementEntity(id = 1L, groupId = groupId, fromMemberId = alice.id, toMemberId = bob.id, amount = 50.0, currency = "EUR", createdAt = 2_000L, status = "COMPLETED")
        )

        val aliceBalance = calculator.calculateMemberBalance(groupId, alice.id)
        val bobBalance = calculator.calculateMemberBalance(groupId, bob.id)
        assertThat(aliceBalance.netBalance).isWithin(0.01).of(0.0)
        assertThat(bobBalance.netBalance).isWithin(0.01).of(0.0)
        assertThat(aliceBalance.isSettled).isTrue()
        assertThat(bobBalance.isSettled).isTrue()
        assertThat(aliceBalance.netBalance + bobBalance.netBalance).isEqualTo(0.0)
    }

    @Test
    fun `partial and multiple repayments preserve zero sum`() = runTest {
        val groupId = 7L
        val alice = GroupMember(id = 10L, groupId = groupId, name = "Alice")
        val bob = GroupMember(id = 20L, groupId = groupId, name = "Bob")
        val expense = GroupExpense(201L, groupId, null, bob.id, 1_000L, "Dinner", 100.0, "EUR", SplitType.EQUAL)
        coEvery { groupDao.getGroupById(groupId) } returns ExpenseGroup(id = groupId, name = "Trip", defaultCurrency = "EUR")
        coEvery { memberDao.getAllForGroup(groupId) } returns listOf(alice, bob)
        coEvery { expenseDao.getExpensesForGroupOnce(groupId) } returns listOf(expense)
        coEvery { settlementDao.getSettlementsForGroup(groupId) } returns listOf(
            GroupSettlementEntity(id = 2L, groupId = groupId, fromMemberId = alice.id, toMemberId = bob.id, amount = 20.0, currency = "EUR", createdAt = 2_000L, status = "RECORDED"),
            GroupSettlementEntity(id = 3L, groupId = groupId, fromMemberId = alice.id, toMemberId = bob.id, amount = 30.0, currency = "EUR", createdAt = 3_000L, status = "RECORDED")
        )
        val aliceBalance = calculator.calculateMemberBalance(groupId, alice.id)
        val bobBalance = calculator.calculateMemberBalance(groupId, bob.id)
        assertThat(aliceBalance.netBalance).isWithin(0.01).of(0.0)
        assertThat(bobBalance.netBalance).isWithin(0.01).of(0.0)
        assertThat(aliceBalance.netBalance + bobBalance.netBalance).isWithin(0.01).of(0.0)
    }

    @Test
    fun `zero repayment and overpayment are not clamped and epsilon is preserved`() = runTest {
        val groupId = 9L
        val alice = GroupMember(id = 10L, groupId = groupId, name = "Alice")
        val bob = GroupMember(id = 20L, groupId = groupId, name = "Bob")
        val expense = GroupExpense(203L, groupId, null, bob.id, 1_000L, "Dinner", 100.0, "EUR", SplitType.EQUAL)
        coEvery { groupDao.getGroupById(groupId) } returns ExpenseGroup(id = groupId, name = "Trip", defaultCurrency = "EUR")
        coEvery { memberDao.getAllForGroup(groupId) } returns listOf(alice, bob)
        coEvery { expenseDao.getExpensesForGroupOnce(groupId) } returns listOf(expense)
        coEvery { settlementDao.getSettlementsForGroup(groupId) } returns emptyList()
        assertThat(calculator.calculateMemberBalance(groupId, alice.id).netBalance).isWithin(0.01).of(-50.0)
        assertThat(calculator.calculateMemberBalance(groupId, bob.id).netBalance).isWithin(0.01).of(50.0)

        coEvery { settlementDao.getSettlementsForGroup(groupId) } returns listOf(
            GroupSettlementEntity(id = 10L, groupId = groupId, fromMemberId = alice.id, toMemberId = bob.id, amount = 0.0, currency = "EUR", createdAt = 2_000L, status = "RECORDED")
        )
        val zeroAlice = calculator.calculateMemberBalance(groupId, alice.id)
        val zeroBob = calculator.calculateMemberBalance(groupId, bob.id)
        assertThat(zeroAlice.netBalance).isEqualTo(-50.0)
        assertThat(zeroBob.netBalance).isEqualTo(50.0)
        assertThat(zeroAlice.netBalance + zeroBob.netBalance).isEqualTo(0.0)

        coEvery { settlementDao.getSettlementsForGroup(groupId) } returns listOf(
            GroupSettlementEntity(id = 7L, groupId = groupId, fromMemberId = alice.id, toMemberId = bob.id, amount = 51.0, currency = "EUR", createdAt = 2_000L, status = "RECORDED")
        )
        val overpaid = calculator.calculateMemberBalance(groupId, alice.id)
        assertThat(overpaid.netBalance).isWithin(0.01).of(1.0)
        assertThat(overpaid.isSettled).isFalse()
        val overpaidRecipient = calculator.calculateMemberBalance(groupId, bob.id)
        assertThat(overpaidRecipient.netBalance).isEqualTo(-1.0)
        assertThat(overpaidRecipient.isSettled).isFalse()
        assertThat(overpaid.netBalance + overpaidRecipient.netBalance).isEqualTo(0.0)

        coEvery { expenseDao.getExpensesForGroupOnce(groupId) } returns emptyList()
        coEvery { settlementDao.getSettlementsForGroup(groupId) } returns listOf(
            GroupSettlementEntity(id = 8L, groupId = groupId, fromMemberId = alice.id, toMemberId = bob.id, amount = 0.01, currency = "EUR", createdAt = 2_000L, status = "RECORDED")
        )
        assertThat(calculator.calculateMemberBalance(groupId, alice.id).isSettled).isTrue()
        assertThat(calculator.calculateMemberBalance(groupId, bob.id).isSettled).isTrue()
        coEvery { settlementDao.getSettlementsForGroup(groupId) } returns listOf(
            GroupSettlementEntity(id = 9L, groupId = groupId, fromMemberId = alice.id, toMemberId = bob.id, amount = 0.02, currency = "EUR", createdAt = 2_000L, status = "RECORDED")
        )
        assertThat(calculator.calculateMemberBalance(groupId, alice.id).isSettled).isFalse()
        assertThat(calculator.calculateMemberBalance(groupId, bob.id).isSettled).isFalse()
    }

    @Test
    fun `departed member remains eligible for historical expense`() = runTest {
        val groupId = 10L
        val alice = GroupMember(id = 10L, groupId = groupId, name = "Alice")
        val carol = GroupMember(id = 30L, groupId = groupId, name = "Carol", leftAt = 2_000L)
        val expense = GroupExpense(204L, groupId, null, alice.id, 1_000L, "Old dinner", 100.0, "EUR", SplitType.EQUAL)
        coEvery { groupDao.getGroupById(groupId) } returns ExpenseGroup(id = groupId, name = "Trip", defaultCurrency = "EUR")
        coEvery { memberDao.getAllForGroup(groupId) } returns listOf(alice, carol)
        coEvery { expenseDao.getExpensesForGroupOnce(groupId) } returns listOf(expense)
        coEvery { settlementDao.getSettlementsForGroup(groupId) } returns emptyList()
        assertThat(calculator.calculateMemberBalance(groupId, carol.id).owedShareTotal).isEqualTo(50.0)
    }
}
