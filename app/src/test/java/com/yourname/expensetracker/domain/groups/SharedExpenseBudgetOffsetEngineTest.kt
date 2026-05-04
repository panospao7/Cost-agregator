package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.GroupDetailsAggregate
import com.yourname.expensetracker.data.repository.GroupsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

class SharedExpenseBudgetOffsetEngineTest {

    private val groupsRepository = mockk<GroupsRepository>()
    private val expenseRepository = mockk<ExpenseRepository>()

    private lateinit var engine: SharedExpenseBudgetOffsetEngine

    @Before
    fun setup() {
        engine = SharedExpenseBudgetOffsetEngine(
            groupsRepository = groupsRepository,
            expenseRepository = expenseRepository,
            ioDispatcher = Dispatchers.Unconfined
            currencySettingsRepository = mock(),
        )
    }

    @Test
    fun `calculateEffectiveBudgetSpend excludes linked legacy system expense from personal spend`() = runTest {
        val periodStart = FIXED_NOW - 30L * DAY_MS
        val periodEnd = FIXED_NOW

        val personalFood = expense(id = 1L, amount = 100.0, categoryId = 1L, isShared = false)
        val personalTravel = expense(id = 2L, amount = 50.0, categoryId = 2L, isShared = false)
        val linkedSharedExpense = expense(id = 3L, amount = 120.0, categoryId = 1L, isShared = false)

        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } returns
            listOf(personalFood, personalTravel, linkedSharedExpense)

        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(
            groupAggregate(
                groupId = 10L,
                members = listOf(
                    GroupMember(id = 100L, groupId = 10L, name = "Me", isCurrentUser = true),
                    GroupMember(id = 101L, groupId = 10L, name = "Alex", isCurrentUser = false)
                currencyConverter = mock(),
                ),
                expenses = listOf(
                    GroupExpense(
                        id = 700L,
                        groupId = 10L,
                        expenseId = 3L,
                        paidById = 100L,
                        date = periodStart + DAY_MS,
                        description = "Shared groceries",
                        totalAmount = 120.0,
                        splitType = SplitType.EQUAL,
                        isReimbursable = true,
                        reimbursedAmount = 20.0
                    )
                )
            )
        )

        val result = engine.calculateEffectiveBudgetSpend(periodStart, periodEnd)

        assertApproxEquals(150.0, result.totalPersonalSpend, 0.0001)
        assertApproxEquals(60.0, result.totalSharedSpend, 0.0001)
        assertApproxEquals(20.0, result.totalReimbursed, 0.0001)
        assertApproxEquals(60.0, result.netSharedLiability, 0.0001)
        assertApproxEquals(210.0, result.effectiveBudgetSpend, 0.0001)
    }

    @Test
    fun `calculateEffectiveBudgetSpend uses SplitCalculator fallback for malformed custom splits`() = runTest {
        val periodStart = FIXED_NOW - 7L * DAY_MS
        val periodEnd = FIXED_NOW

        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } returns emptyList()
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(
            groupAggregate(
                groupId = 10L,
                members = listOf(
                    GroupMember(id = 100L, groupId = 10L, name = "Me", isCurrentUser = true),
                    GroupMember(id = 101L, groupId = 10L, name = "Alex", isCurrentUser = false)
                ),
                expenses = listOf(
                    GroupExpense(
                        id = 701L,
                        groupId = 10L,
                        expenseId = null,
                        paidById = 101L,
                        date = periodStart + DAY_MS,
                        description = "Broken custom split",
                        totalAmount = 90.0,
                        splitType = SplitType.CUSTOM_AMOUNT,
                        customSplitsJson = "100:bad-data"
                    )
                )
            )
        )

        val result = engine.calculateEffectiveBudgetSpend(periodStart, periodEnd)

        assertApproxEquals(0.0, result.totalPersonalSpend, 0.0)
        assertApproxEquals(45.0, result.totalSharedSpend, 0.0001)
        assertApproxEquals(45.0, result.effectiveBudgetSpend, 0.0001)
    }

    @Test
    fun `calculateEffectiveBudgetSpend avoids N+1 by fetching expenses once and mapping in memory`() = runTest {
        val periodStart = FIXED_NOW - 7L * DAY_MS
        val periodEnd = FIXED_NOW

        val allExpenses = listOf(
            expense(id = 1L, amount = 40.0, categoryId = 1L, isShared = false),
            expense(id = 2L, amount = 80.0, categoryId = 2L, isShared = true),
            expense(id = 3L, amount = 90.0, categoryId = 3L, isShared = true)
        )
        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } returns allExpenses

        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(
            groupAggregate(
                groupId = 1L,
                members = listOf(
                    GroupMember(id = 10L, groupId = 1L, name = "Me", isCurrentUser = true),
                    GroupMember(id = 11L, groupId = 1L, name = "A")
                ),
                expenses = listOf(
                    GroupExpense(
                        id = 101L,
                        groupId = 1L,
                        expenseId = 2L,
                        paidById = 11L,
                        date = periodStart + 1,
                        description = "Dinner",
                        totalAmount = 80.0,
                        splitType = SplitType.EQUAL
                    )
                )
            ),
            groupAggregate(
                groupId = 2L,
                members = listOf(
                    GroupMember(id = 20L, groupId = 2L, name = "Me", isCurrentUser = true),
                    GroupMember(id = 21L, groupId = 2L, name = "B")
                ),
                expenses = listOf(
                    GroupExpense(
                        id = 102L,
                        groupId = 2L,
                        expenseId = 3L,
                        paidById = 21L,
                        date = periodStart + 2,
                        description = "Taxi",
                        totalAmount = 90.0,
                        splitType = SplitType.EQUAL
                    )
                )
            )
        )

        engine.calculateEffectiveBudgetSpend(periodStart, periodEnd)

        coVerify(exactly = 1) { expenseRepository.getExpensesBetween(periodStart, periodEnd) }
        coVerify(exactly = 1) { groupsRepository.getActiveGroupsWithDetails() }
    }

    @Test
    fun `calculateEffectiveBudgetSpend applies category filtering for personal and shared expenses`() = runTest {
        val periodStart = FIXED_NOW - 10L * DAY_MS
        val periodEnd = FIXED_NOW

        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } returns listOf(
            expense(id = 1L, amount = 100.0, categoryId = 1L, isShared = false),
            expense(id = 2L, amount = 60.0, categoryId = 2L, isShared = false),
            expense(id = 3L, amount = 80.0, categoryId = 1L, isShared = true),
            expense(id = 4L, amount = 50.0, categoryId = 2L, isShared = true)
        )
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(
            groupAggregate(
                groupId = 5L,
                members = listOf(
                    GroupMember(id = 501L, groupId = 5L, name = "Me", isCurrentUser = true),
                    GroupMember(id = 502L, groupId = 5L, name = "Friend")
                ),
                expenses = listOf(
                    GroupExpense(
                        id = 201L,
                        groupId = 5L,
                        expenseId = 3L,
                        paidById = 502L,
                        date = periodStart + DAY_MS,
                        description = "Food split",
                        totalAmount = 80.0,
                        splitType = SplitType.EQUAL
                    ),
                    GroupExpense(
                        id = 202L,
                        groupId = 5L,
                        expenseId = 4L,
                        paidById = 502L,
                        date = periodStart + DAY_MS,
                        description = "Travel split",
                        totalAmount = 50.0,
                        splitType = SplitType.EQUAL
                    )
                )
            )
        )

        val onlyCategory1 = engine.calculateEffectiveBudgetSpend(periodStart, periodEnd, categoryId = 1L)

        assertApproxEquals(100.0, onlyCategory1.totalPersonalSpend, 0.0001)
        assertApproxEquals(40.0, onlyCategory1.totalSharedSpend, 0.0001)
        assertApproxEquals(140.0, onlyCategory1.effectiveBudgetSpend, 0.0001)
    }

    @Test
    fun `calculateEffectiveBudgetSpend edge case no groups returns personal only`() = runTest {
        val periodStart = FIXED_NOW - 5L * DAY_MS
        val periodEnd = FIXED_NOW

        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } returns listOf(
            expense(id = 1L, amount = 35.0, categoryId = 1L, isShared = false)
        )
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns emptyList()

        val result = engine.calculateEffectiveBudgetSpend(periodStart, periodEnd)

        assertApproxEquals(35.0, result.totalPersonalSpend, 0.0001)
        assertApproxEquals(0.0, result.totalSharedSpend, 0.0)
        assertApproxEquals(35.0, result.effectiveBudgetSpend, 0.0001)
    }

    @Test
    fun `calculateEffectiveBudgetSpend edge case no shared expenses in groups`() = runTest {
        val periodStart = FIXED_NOW - 5L * DAY_MS
        val periodEnd = FIXED_NOW

        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } returns listOf(
            expense(id = 1L, amount = 20.0, categoryId = 1L, isShared = false)
        )
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(
            groupAggregate(
                groupId = 9L,
                members = listOf(
                    GroupMember(id = 901L, groupId = 9L, name = "Me", isCurrentUser = true),
                    GroupMember(id = 902L, groupId = 9L, name = "Friend")
                ),
                expenses = emptyList()
            )
        )

        val result = engine.calculateEffectiveBudgetSpend(periodStart, periodEnd)

        assertApproxEquals(20.0, result.totalPersonalSpend, 0.0001)
        assertApproxEquals(0.0, result.totalSharedSpend, 0.0)
        assertApproxEquals(20.0, result.effectiveBudgetSpend, 0.0001)
    }

    @Test
    fun `calculateEffectiveBudgetSpend edge case empty period returns zeros`() = runTest {
        val periodStart = FIXED_NOW
        val periodEnd = FIXED_NOW

        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } returns emptyList()
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns emptyList()

        val result = engine.calculateEffectiveBudgetSpend(periodStart, periodEnd)

        assertApproxEquals(0.0, result.totalPersonalSpend, 0.0)
        assertApproxEquals(0.0, result.totalSharedSpend, 0.0)
        assertApproxEquals(0.0, result.totalReimbursed, 0.0)
        assertApproxEquals(0.0, result.netSharedLiability, 0.0)
        assertApproxEquals(0.0, result.effectiveBudgetSpend, 0.0)
    }

    @Test
    fun `calculateEffectiveBudgetSpend propagates repository failures`() = runTest {
        val periodStart = FIXED_NOW - 5L * DAY_MS
        val periodEnd = FIXED_NOW

        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } throws IllegalStateException("boom")

        assertFailsWith<IllegalStateException> {
            engine.calculateEffectiveBudgetSpend(periodStart, periodEnd)
        }
    }

    private fun groupAggregate(
        groupId: Long,
        members: List<GroupMember>,
        expenses: List<GroupExpense>
    ): GroupDetailsAggregate = GroupDetailsAggregate(
        group = ExpenseGroup(id = groupId, name = "Group $groupId"),
        members = members,
        expenses = expenses
    )

    private fun expense(
        id: Long,
        amount: Double,
        categoryId: Long,
        isShared: Boolean
    ): Expense = Expense(
        id = id,
        amount = amount,
        merchant = "M$id",
        transactionType = TransactionType.PURCHASE,
        date = FIXED_NOW - DAY_MS,
        categoryId = categoryId,
        isSharedExpense = isShared,
        isNotMine = false
    )

    companion object {
        private const val FIXED_NOW = 1_730_000_000_000L
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}