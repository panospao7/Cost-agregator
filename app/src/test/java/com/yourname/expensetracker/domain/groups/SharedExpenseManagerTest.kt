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

    private enum class RemoveOutcome { NOT_FOUND, PAID_EXPENSES, SPLIT_REFERENCE, SUCCESS }

    private data class RemoveMemberRow(
        val name: String,
        val target: SharedExpenseMember,
        val members: List<SharedExpenseMember>,
        val expenses: List<SharedGroupExpense>,
        val outcome: RemoveOutcome,
        val expectedExpenseCount: Int = 0
    )

    private fun removeMemberRows(): List<RemoveMemberRow> {
        val joinedAt = 1_700_000_000_000L
        fun expense(groupId: Long, expenseId: Long, paidById: Long, date: Long, splitType: GroupSplitType, splits: String? = null) =
            SharedGroupExpense(
                id = 1L, groupId = groupId, expenseId = expenseId, paidById = paidById, date = date,
                description = "row", totalAmount = 30.0, currency = "EUR", splitType = splitType,
                customSplitsSerialized = splits
            )

        val absent = SharedExpenseMember(id = 99L, groupId = 5L, name = "Ghost")
        val payer = SharedExpenseMember(id = 2L, groupId = 7L, name = "B")
        val referenced = SharedExpenseMember(id = 2L, groupId = 9L, name = "B")
        val lateEqualOnJoin = SharedExpenseMember(id = 2L, groupId = 10L, name = "B", joinedAt = joinedAt)
        val unused = SharedExpenseMember(id = 2L, groupId = 11L, name = "B")
        val lateEqualBefore = SharedExpenseMember(id = 2L, groupId = 12L, name = "B", joinedAt = joinedAt)
        return listOf(
            RemoveMemberRow(
                "member absent returns Member not found", absent,
                listOf(SharedExpenseMember(id = 1L, groupId = 5L, name = "A")), emptyList(), RemoveOutcome.NOT_FOUND
            ),
            RemoveMemberRow(
                "member who paid an expense is blocked", payer, listOf(payer),
                listOf(expense(7L, 12L, paidById = 2L, date = 0L, splitType = GroupSplitType.EQUAL)),
                RemoveOutcome.PAID_EXPENSES, expectedExpenseCount = 1
            ),
            RemoveMemberRow(
                "member referenced in custom splits is blocked", referenced,
                listOf(
                    SharedExpenseMember(id = 1L, groupId = 9L, name = "A"), referenced,
                    SharedExpenseMember(id = 3L, groupId = 9L, name = "C")
                ),
                listOf(expense(9L, 23L, paidById = 1L, date = 0L, splitType = GroupSplitType.CUSTOM_AMOUNT, splits = "1:10,2:10,3:10")),
                RemoveOutcome.SPLIT_REFERENCE, expectedExpenseCount = 1
            ),
            RemoveMemberRow(
                "equal split expense exactly at joinedAt is blocked", lateEqualOnJoin,
                listOf(SharedExpenseMember(id = 1L, groupId = 10L, name = "A", joinedAt = joinedAt - 10_000L), lateEqualOnJoin),
                listOf(expense(10L, 24L, paidById = 1L, date = joinedAt, splitType = GroupSplitType.EQUAL)),
                RemoveOutcome.SPLIT_REFERENCE, expectedExpenseCount = 1
            ),
            RemoveMemberRow(
                "no paid expenses and no split references succeeds", unused,
                listOf(SharedExpenseMember(id = 1L, groupId = 11L, name = "A"), unused), emptyList(), RemoveOutcome.SUCCESS
            ),
            RemoveMemberRow(
                "equal split expense one ms before joinedAt is ignored and succeeds", lateEqualBefore,
                listOf(SharedExpenseMember(id = 1L, groupId = 12L, name = "A", joinedAt = joinedAt - 10_000L), lateEqualBefore),
                listOf(expense(12L, 25L, paidById = 1L, date = joinedAt - 1L, splitType = GroupSplitType.EQUAL)),
                RemoveOutcome.SUCCESS
            )
        )
    }

    @Test
    fun `removeMember table covers not found paid expenses split references and success`() = runTest(testDispatcher) {
        val rows = removeMemberRows()
        assertEquals(6, rows.size)

        rows.forEach { row ->
            // Fresh port and manager per row: removeMember call counts are verified.
            val port = mockk<SharedExpenseDataPort>(relaxed = true)
            every { port.getAllGroups() } returns flowOf(emptyList())
            every { port.getActiveGroups() } returns flowOf(emptyList())
            val rowManager = SharedExpenseManager(
                sharedExpenseDataPort = port,
                timeProvider = timeProvider,
                currencySettingsRepository = mockk(),
                ioDispatcher = testDispatcher
            )
            val groupId = row.target.groupId
            coEvery { port.getGroupMembersOnce(groupId) } returns row.members
            coEvery { port.getGroupExpensesOnce(groupId) } returns row.expenses

            val result = rowManager.removeMember(row.target)

            when (row.outcome) {
                RemoveOutcome.NOT_FOUND -> {
                    assertTrue("${row.name}: expected Error but was $result", result is RemoveSharedExpenseMemberResult.Error)
                    assertEquals("${row.name}: message", "Member not found", (result as RemoveSharedExpenseMemberResult.Error).message)
                }
                RemoveOutcome.PAID_EXPENSES -> {
                    assertTrue("${row.name}: expected CannotDeleteMemberWithExpenses but was $result", result is RemoveSharedExpenseMemberResult.CannotDeleteMemberWithExpenses)
                    assertEquals("${row.name}: expenseCount", row.expectedExpenseCount, (result as RemoveSharedExpenseMemberResult.CannotDeleteMemberWithExpenses).expenseCount)
                }
                RemoveOutcome.SPLIT_REFERENCE -> {
                    assertTrue("${row.name}: expected CannotDeleteMemberReferencedInSplits but was $result", result is RemoveSharedExpenseMemberResult.CannotDeleteMemberReferencedInSplits)
                    assertEquals("${row.name}: expenseCount", row.expectedExpenseCount, (result as RemoveSharedExpenseMemberResult.CannotDeleteMemberReferencedInSplits).expenseCount)
                }
                RemoveOutcome.SUCCESS ->
                    assertTrue("${row.name}: expected Success but was $result", result is RemoveSharedExpenseMemberResult.Success)
            }
            val expectedRemovals = if (row.outcome == RemoveOutcome.SUCCESS) 1 else 0
            coVerify(exactly = expectedRemovals) { port.removeMember(any()) }
            if (row.outcome == RemoveOutcome.SUCCESS) {
                coVerify(exactly = 1) { port.removeMember(row.target) }
            }
        }
    }

    @Test
    fun `addExpense rejects non finite custom split values`() = runTest(testDispatcher) {
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns listOf(
            SharedExpenseMember(id = 1L, groupId = 1L, name = "Me", isCurrentUser = true),
            SharedExpenseMember(id = 2L, groupId = 1L, name = "Alex")
        )
        try {
            manager.addExpense(
                groupId = 1L,
                expenseId = 10L,
                paidById = 1L,
                description = "Bad split",
                totalAmount = 100.0,
                splitType = GroupSplitType.CUSTOM_AMOUNT,
                customSplits = mapOf(1L to Double.NaN, 2L to 50.0),
                currency = "EUR"
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
