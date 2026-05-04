package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.domain.logic.SplitCalculator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

class SharedExpenseManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val sharedExpenseDataPort = mockk<SharedExpenseDataPort>(relaxed = true)
    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = 1000L }

    private lateinit var manager: SharedExpenseManager

    @Before
    fun setUp() {
        every { sharedExpenseDataPort.getAllGroups() } returns flowOf(emptyList())
        every { sharedExpenseDataPort.getActiveGroups() } returns flowOf(emptyList())
        manager = SharedExpenseManager(
            sharedExpenseDataPort = sharedExpenseDataPort,
            timeProvider = timeProvider,
            currencySettingsRepository = mockk(),
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun `calculateBalances dispatches all split types and computes expected net balances`() = runTest(testDispatcher) {
        val groupId = 1L
        val members = listOf(
            SharedExpenseMember(id = 1L, groupId = groupId, name = "A", isCurrentUser = true),
            SharedExpenseMember(id = 2L, groupId = groupId, name = "B"),
            SharedExpenseMember(id = 3L, groupId = groupId, name = "C")
        )
        val expenses = listOf(
            SharedGroupExpense(
                id = 1L,
                groupId = groupId,
                expenseId = 101L,
                paidById = 1L,
                date = 0L,
                description = "Equal",
                totalAmount = 90.0,
                currency = "EUR",
                splitType = GroupSplitType.EQUAL,
            ),
            SharedGroupExpense(
                id = 2L,
                groupId = groupId,
                expenseId = 102L,
                paidById = 2L,
                date = 0L,
                description = "Amount",
                totalAmount = 60.0,
                currency = "EUR",
                splitType = GroupSplitType.CUSTOM_AMOUNT,
                customSplitsSerialized = "1:10,2:20,3:30",
            ),
            SharedGroupExpense(
                id = 3L,
                groupId = groupId,
                expenseId = 103L,
                paidById = 3L,
                date = 0L,
                description = "Percent",
                totalAmount = 120.0,
                currency = "EUR",
                splitType = GroupSplitType.CUSTOM_PERCENT,
                customSplitsSerialized = "1:50,2:25,3:25",
            ),
            SharedGroupExpense(
                id = 4L,
                groupId = groupId,
                expenseId = 104L,
                paidById = 1L,
                date = 0L,
                description = "Unequal",
                totalAmount = 40.0,
                currency = "EUR",
                splitType = GroupSplitType.UNEQUAL,
                customSplitsSerialized = "1:30,2:5,3:5",
            )
        )

        coEvery { sharedExpenseDataPort.getGroupMembersOnce(groupId) } returns members
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(groupId) } returns expenses

        val balances = manager.calculateBalances(groupId)

        assertApproxEquals(130.0, balances[1L]?.paid ?: 0.0, 0.01)
        assertApproxEquals(130.0, balances[1L]?.shouldPay ?: 0.0, 0.01)
        assertApproxEquals(0.0, balances[1L]?.netBalance ?: 1.0, 0.01)

        assertApproxEquals(60.0, balances[2L]?.paid ?: 0.0, 0.01)
        assertApproxEquals(85.0, balances[2L]?.shouldPay ?: 0.0, 0.01)
        assertApproxEquals(-25.0, balances[2L]?.netBalance ?: 0.0, 0.01)

        assertApproxEquals(120.0, balances[3L]?.paid ?: 0.0, 0.01)
        assertApproxEquals(95.0, balances[3L]?.shouldPay ?: 0.0, 0.01)
        assertApproxEquals(25.0, balances[3L]?.netBalance ?: 0.0, 0.01)
    }

    @Test
    fun `crash test 4_9 split parity with SplitCalculator produces identical net balances bug B_02`() = runTest(testDispatcher) {
        val groupId = 1L
        val members = listOf(
            SharedExpenseMember(id = 1L, groupId = groupId, name = "A", isCurrentUser = true),
            SharedExpenseMember(id = 2L, groupId = groupId, name = "B"),
            SharedExpenseMember(id = 3L, groupId = groupId, name = "C")
        )
        val sharedExpenses = listOf(
            SharedGroupExpense(id = 1L, groupId = groupId, expenseId = 101L, paidById = 1L, date = 0L, description = "e1", totalAmount = 90.0, splitType = GroupSplitType.EQUAL, currency = "EUR"),
            SharedGroupExpense(id = 2L, groupId = groupId, expenseId = 102L, paidById = 2L, date = 0L, description = "e2", totalAmount = 60.0, splitType = GroupSplitType.CUSTOM_AMOUNT, customSplitsSerialized = "1:10,2:20,3:30", currency = "EUR"),
            SharedGroupExpense(id = 3L, groupId = groupId, expenseId = 103L, paidById = 3L, date = 0L, description = "e3", totalAmount = 100.0, splitType = GroupSplitType.CUSTOM_PERCENT, customSplitsSerialized = "1:33.33,2:33.33,3:33.34", currency = "EUR"),
            SharedGroupExpense(id = 4L, groupId = groupId, expenseId = 104L, paidById = 1L, date = 0L, description = "e4", totalAmount = 40.0, splitType = GroupSplitType.UNEQUAL, customSplitsSerialized = "1:30,2:5,3:5", currency = "EUR"),
            SharedGroupExpense(id = 5L, groupId = groupId, expenseId = 105L, paidById = 2L, date = 0L, description = "e5", totalAmount = 25.0, splitType = GroupSplitType.EQUAL, currency = "EUR")
        )

        coEvery { sharedExpenseDataPort.getGroupMembersOnce(groupId) } returns members
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(groupId) } returns sharedExpenses

        val managerBalances = manager.calculateBalances(groupId)

        val splitMembers = members.map {
            GroupMember(
                id = it.id,
                groupId = it.groupId,
                name = it.name,
                isCurrentUser = it.isCurrentUser,
                joinedAt = it.joinedAt
            )
        }
        val splitExpenses = sharedExpenses.map {
            GroupExpense(
                id = it.id,
                groupId = it.groupId,
                expenseId = it.expenseId,
                paidById = it.paidById,
                date = it.date,
                description = it.description,
                totalAmount = it.totalAmount,
                splitType = when (it.splitType) {
                    GroupSplitType.EQUAL -> SplitType.EQUAL
                    GroupSplitType.CUSTOM_AMOUNT -> SplitType.CUSTOM_AMOUNT
                    GroupSplitType.CUSTOM_PERCENT -> SplitType.CUSTOM_PERCENT
                    GroupSplitType.UNEQUAL -> SplitType.UNEQUAL
                },
                customSplitsJson = it.customSplitsSerialized
            )
        }

        val splitCalculatorBalances = SplitCalculator.calculateBalances(splitExpenses, splitMembers)

        splitMembers.forEach { member ->
            val fromManager = managerBalances[member.id]?.netBalance ?: 0.0
            val fromSplitCalculator = splitCalculatorBalances[member.id] ?: 0.0
            assertApproxEquals(fromSplitCalculator, fromManager, 0.01)
        }
    }

    @Test
    fun `calculateBalances uses joinedAt aware SplitCalculator for backdated equal splits`() = runTest(testDispatcher) {
        val groupId = 2L
        val expenseDate = 1_000L
        val members = listOf(
            SharedExpenseMember(id = 1L, groupId = groupId, name = "A", joinedAt = 500L),
            SharedExpenseMember(id = 2L, groupId = groupId, name = "B", joinedAt = 900L),
            SharedExpenseMember(id = 3L, groupId = groupId, name = "Late", joinedAt = 1_500L)
        )
        val expenses = listOf(
            SharedGroupExpense(
                id = 1L,
                groupId = groupId,
                expenseId = 201L,
                paidById = 1L,
                date = expenseDate,
                description = "Dinner",
                totalAmount = 90.0,
                currency = "EUR",
                splitType = GroupSplitType.EQUAL,
            )
        )

        coEvery { sharedExpenseDataPort.getGroupMembersOnce(groupId) } returns members
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(groupId) } returns expenses

        val balances = manager.calculateBalances(groupId)

        assertApproxEquals(90.0, balances[1L]?.paid ?: 0.0, 0.01)
        assertApproxEquals(45.0, balances[1L]?.shouldPay ?: 0.0, 0.01)
        assertApproxEquals(45.0, balances[1L]?.netBalance ?: 0.0, 0.01)

        assertApproxEquals(0.0, balances[2L]?.paid ?: 1.0, 0.01)
        assertApproxEquals(45.0, balances[2L]?.shouldPay ?: 0.0, 0.01)
        assertApproxEquals(-45.0, balances[2L]?.netBalance ?: 0.0, 0.01)

        assertApproxEquals(0.0, balances[3L]?.paid ?: 1.0, 0.01)
        assertApproxEquals(0.0, balances[3L]?.shouldPay ?: 1.0, 0.01)
        assertApproxEquals(0.0, balances[3L]?.netBalance ?: 1.0, 0.01)
    }

    @Test
    fun `removeMember returns member not found when member is absent`() = runTest(testDispatcher) {
        val member = SharedExpenseMember(id = 99L, groupId = 5L, name = "Ghost")
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(5L) } returns listOf(
            SharedExpenseMember(id = 1L, groupId = 5L, name = "A")
        )

        val result = manager.removeMember(member)

        assertTrue(result is RemoveSharedExpenseMemberResult.Error)
        result as RemoveSharedExpenseMemberResult.Error
        assertEquals("Member not found", result.message)
    }

    @Test
    fun `removeMember blocks deletion when member has paid expenses`() = runTest(testDispatcher) {
        val member = SharedExpenseMember(id = 2L, groupId = 7L, name = "B")
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(7L) } returns listOf(member)
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(7L) } returns listOf(
            SharedGroupExpense(
                id = 1L,
                groupId = 7L,
                expenseId = 12L,
                paidById = 2L,
                date = 0L,
                description = "paid",
                totalAmount = 10.0,
                currency = "EUR",
                splitType = GroupSplitType.EQUAL,
            )
        )

        val result = manager.removeMember(member)

        assertTrue(result is RemoveSharedExpenseMemberResult.CannotDeleteMemberWithExpenses)
        result as RemoveSharedExpenseMemberResult.CannotDeleteMemberWithExpenses
        assertEquals(1, result.expenseCount)
    }

    @Test
    fun `removeMember blocks deletion when member is referenced in custom splits`() = runTest(testDispatcher) {
        val target = SharedExpenseMember(id = 2L, groupId = 9L, name = "B")
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(9L) } returns listOf(
            SharedExpenseMember(id = 1L, groupId = 9L, name = "A"),
            target,
            SharedExpenseMember(id = 3L, groupId = 9L, name = "C")
        )
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(9L) } returns listOf(
            SharedGroupExpense(
                id = 1L,
                groupId = 9L,
                expenseId = 23L,
                paidById = 1L,
                date = 0L,
                description = "custom",
                totalAmount = 30.0,
                currency = "EUR",
                splitType = GroupSplitType.CUSTOM_AMOUNT,
                customSplitsSerialized = "1:10,2:10,3:10",
            )
        )

        val result = manager.removeMember(target)

        assertTrue(result is RemoveSharedExpenseMemberResult.CannotDeleteMemberReferencedInSplits)
        result as RemoveSharedExpenseMemberResult.CannotDeleteMemberReferencedInSplits
        assertEquals(1, result.expenseCount)
    }

    @Test
    fun `removeMember blocks deletion when equal split expense is on or after joinedAt`() = runTest(testDispatcher) {
        val joinedAt = 1_700_000_000_000L
        val target = SharedExpenseMember(id = 2L, groupId = 10L, name = "B", joinedAt = joinedAt)
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(10L) } returns listOf(
            SharedExpenseMember(id = 1L, groupId = 10L, name = "A", joinedAt = joinedAt - 10_000L),
            target
        )
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(10L) } returns listOf(
            SharedGroupExpense(
                id = 1L,
                groupId = 10L,
                expenseId = 24L,
                paidById = 1L,
                date = joinedAt,
                description = "equal",
                totalAmount = 30.0,
                currency = "EUR",
                splitType = GroupSplitType.EQUAL,
            )
        )

        val result = manager.removeMember(target)

        assertTrue(result is RemoveSharedExpenseMemberResult.CannotDeleteMemberReferencedInSplits)
        result as RemoveSharedExpenseMemberResult.CannotDeleteMemberReferencedInSplits
        assertEquals(1, result.expenseCount)
    }

    @Test
    fun `removeMember succeeds when member has no paid expenses and no split references`() = runTest(testDispatcher) {
        val target = SharedExpenseMember(id = 2L, groupId = 11L, name = "B")
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(11L) } returns listOf(
            SharedExpenseMember(id = 1L, groupId = 11L, name = "A"),
            target
        )
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(11L) } returns emptyList()

        val result = manager.removeMember(target)

        assertTrue(result is RemoveSharedExpenseMemberResult.Success)
        coVerify(exactly = 1) { sharedExpenseDataPort.removeMember(target) }
    }

    @Test
    fun `removeMember ignores equal split expenses before joinedAt`() = runTest(testDispatcher) {
        val joinedAt = 1_700_000_000_000L
        val target = SharedExpenseMember(id = 2L, groupId = 12L, name = "B", joinedAt = joinedAt)
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(12L) } returns listOf(
            SharedExpenseMember(id = 1L, groupId = 12L, name = "A", joinedAt = joinedAt - 10_000L),
            target
        )
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(12L) } returns listOf(
            SharedGroupExpense(
                id = 1L,
                groupId = 12L,
                expenseId = 25L,
                paidById = 1L,
                date = joinedAt - 1L,
                description = "equal",
                totalAmount = 30.0,
                currency = "EUR",
                splitType = GroupSplitType.EQUAL,
            )
        )

        val result = manager.removeMember(target)

        assertTrue(result is RemoveSharedExpenseMemberResult.Success)
        coVerify(exactly = 1) { sharedExpenseDataPort.removeMember(target) }
    }

    @Test
    fun `addExpense rejects non finite custom split values`() = runTest(testDispatcher) {
        try {
            manager.addExpense(
                groupId = 1L,
                expenseId = 10L,
                paidById = 1L,
                description = "Bad split",
                totalAmount = 100.0,
                splitType = GroupSplitType.CUSTOM_AMOUNT,
                customSplits = mapOf(1L to Double.NaN, 2L to 50.0, currency = "EUR")
            )
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("must be finite") == true)
        }
    }

    @Test
    fun `addExpense rejects blank description`() = runTest(testDispatcher) {
        val error = assertFailsWith<IllegalArgumentException> {
            manager.addExpense(
                groupId = 1L,
                expenseId = 10L,
                paidById = 1L,
                description = "   ",
                totalAmount = 100.0,
                currency = "EUR",
                splitType = GroupSplitType.EQUAL,
            )
        }

        assertEquals("Description cannot be blank", error.message)
    }

    @Test
    fun `addExpense rejects non positive or non finite amount`() = runTest(testDispatcher) {
        val invalidAmounts = listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)

        invalidAmounts.forEach { amount ->
            val error = assertFailsWith<IllegalArgumentException> {
                manager.addExpense(
                    groupId = 1L,
                    expenseId = 10L,
                    paidById = 1L,
                    description = "Taxi",
                    totalAmount = amount,
                    currency = "EUR",
                    splitType = GroupSplitType.EQUAL,
                )
            }
            assertEquals("Amount must be a positive finite number", error.message)
        }
    }

    @Test
    fun `addExpense rejects payer outside group membership`() = runTest(testDispatcher) {
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns listOf(
            SharedExpenseMember(id = 1L, groupId = 1L, name = "Me"),
            SharedExpenseMember(id = 2L, groupId = 1L, name = "Alex")
        )

        val error = assertFailsWith<IllegalArgumentException> {
            manager.addExpense(
                groupId = 1L,
                expenseId = 10L,
                paidById = 99L,
                description = "Dinner",
                totalAmount = 100.0,
                currency = "EUR",
                splitType = GroupSplitType.EQUAL,
            )
        }

        assertEquals("Payer is not a member of this group", error.message)
    }

    @Test
    fun `addExpense uses group default currency from data port`() = runTest(testDispatcher) {
        val captured = mutableListOf<SharedGroupExpense>()
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns listOf(
            SharedExpenseMember(id = 1L, groupId = 1L, name = "Me"),
            SharedExpenseMember(id = 2L, groupId = 1L, name = "Alex")
        )
        coEvery { sharedExpenseDataPort.getGroupOnce(1L) } returns SharedExpenseGroup(
            id = 1L,
            name = "Trip",
            defaultCurrency = "USD"
        )
        coEvery { sharedExpenseDataPort.addExpense(any()) } answers {
            captured += firstArg<SharedGroupExpense>()
            999L
        }

        manager.addExpense(
            groupId = 1L,
            expenseId = 100L,
            paidById = 1L,
            description = "Taxi",
            totalAmount = 25.0,
            currency = "EUR",
            splitType = GroupSplitType.EQUAL
        )

        assertEquals(1, captured.size)
        assertEquals("USD", captured.single().currency)
        assertApproxEquals(25.0, captured.single().totalAmount, 0.01)
    }
}