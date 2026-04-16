package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.domain.groups.GroupCreationResult
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.groups.GroupTransactionCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupsRepositoryImplTest {

    private val database = mockk<AppDatabase>(relaxed = true)
    private val groupDao = mockk<ExpenseGroupDao>(relaxed = true)
    private val memberDao = mockk<GroupMemberDao>(relaxed = true)
    private val groupExpenseDao = mockk<GroupExpenseDao>(relaxed = true)
    private val coordinator = mockk<GroupTransactionCoordinator>(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: GroupsRepositoryImpl

    @Before
    fun setUp() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionBlock = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(transactionBlock)) } coAnswers {
            transactionBlock.captured.invoke()
        }

        repository = GroupsRepositoryImpl(
            database = database,
            groupDao = groupDao,
            memberDao = memberDao,
            groupExpenseDao = groupExpenseDao,
            coordinator = coordinator,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun `create group returns group with members`() = runTest(testDispatcher) {
        coEvery {
            coordinator.createGroupWithMembers(
                name = "Trip",
                description = "Summer trip",
                currency = "EUR",
                members = any()
            )
        } returns GroupCreationResult.Success(groupId = 101L)

        val result = repository.createGroup(
            name = "Trip",
            description = "Summer trip",
            currency = "EUR",
            currentUserName = "Panos"
        )

        assertTrue(result is GroupCreationResult.Success)
        assertEquals(101L, (result as GroupCreationResult.Success).groupId)
        coVerify(exactly = 1) {
            coordinator.createGroupWithMembers(
                name = "Trip",
                description = "Summer trip",
                currency = "EUR",
                members = match { members ->
                    members.size == 1 &&
                        members[0].name == "Panos" &&
                        members[0].isCurrentUser &&
                        members[0].groupId == 0L
                }
            )
        }
    }

    @Test
    fun `member delete with split references returns error`() = runTest(testDispatcher) {
        val groupId = 55L
        val memberId = 10L

        coEvery { memberDao.getById(memberId) } returns GroupMember(id = memberId, groupId = groupId, name = "Alice")
        coEvery { memberDao.getAllForGroup(groupId) } returns listOf(
            GroupMember(id = memberId, groupId = groupId, name = "Alice"),
            GroupMember(id = 20L, groupId = groupId, name = "Bob")
        )
        coEvery { groupExpenseDao.getExpensesForGroupOnce(groupId) } returns listOf(
            GroupExpense(
                id = 999L,
                groupId = groupId,
                expenseId = 42L,
                paidById = 20L,
                date = 1_700_000_000_000L,
                description = "Dinner",
                totalAmount = 100.0,
                currency = "EUR",
                splitType = SplitType.CUSTOM_AMOUNT,
                customSplitsJson = "10:40,20:60"
            )
        )

        val result = repository.deleteMember(groupId = groupId, memberId = memberId)

        assertTrue(result is DeleteGroupMemberResult.CannotDeleteMemberReferencedInSplits)
        assertEquals(1, (result as DeleteGroupMemberResult.CannotDeleteMemberReferencedInSplits).expenseCount)
    }

    @Test
    fun `member delete blocks equal split expenses on or after joinedAt`() = runTest(testDispatcher) {
        val groupId = 56L
        val memberId = 10L
        val joinedAt = 1_700_000_000_000L
        val targetMember = GroupMember(id = memberId, groupId = groupId, name = "Alice", joinedAt = joinedAt)

        coEvery { memberDao.getById(memberId) } returns targetMember
        coEvery { memberDao.getAllForGroup(groupId) } returns listOf(
            targetMember,
            GroupMember(id = 20L, groupId = groupId, name = "Bob", joinedAt = joinedAt - 10_000L)
        )
        coEvery { groupExpenseDao.countExpensesPaidByMember(groupId, memberId) } returns 0
        coEvery { groupExpenseDao.getExpensesForGroupOnce(groupId) } returns listOf(
            GroupExpense(
                id = 1000L,
                groupId = groupId,
                expenseId = 43L,
                paidById = 20L,
                date = joinedAt,
                description = "Dinner",
                totalAmount = 100.0,
                currency = "EUR",
                splitType = SplitType.EQUAL,
                customSplitsJson = null
            )
        )

        val result = repository.deleteMember(groupId = groupId, memberId = memberId)

        assertTrue(result is DeleteGroupMemberResult.CannotDeleteMemberReferencedInSplits)
        assertEquals(1, (result as DeleteGroupMemberResult.CannotDeleteMemberReferencedInSplits).expenseCount)
        coVerify(exactly = 0) { memberDao.delete(any()) }
    }

    @Test
    fun `member delete ignores equal split expenses before joinedAt`() = runTest(testDispatcher) {
        val groupId = 57L
        val memberId = 10L
        val joinedAt = 1_700_000_000_000L
        val targetMember = GroupMember(id = memberId, groupId = groupId, name = "Alice", joinedAt = joinedAt)

        coEvery { memberDao.getById(memberId) } returns targetMember
        coEvery { memberDao.getAllForGroup(groupId) } returns listOf(
            targetMember,
            GroupMember(id = 20L, groupId = groupId, name = "Bob", joinedAt = joinedAt - 10_000L)
        )
        coEvery { groupExpenseDao.countExpensesPaidByMember(groupId, memberId) } returns 0
        coEvery { groupExpenseDao.getExpensesForGroupOnce(groupId) } returns listOf(
            GroupExpense(
                id = 1001L,
                groupId = groupId,
                expenseId = 44L,
                paidById = 20L,
                date = joinedAt - 1L,
                description = "Older dinner",
                totalAmount = 100.0,
                currency = "EUR",
                splitType = SplitType.EQUAL,
                customSplitsJson = null
            )
        )

        val result = repository.deleteMember(groupId = groupId, memberId = memberId)

        assertTrue(result is DeleteGroupMemberResult.Success)
        coVerify(exactly = 1) { memberDao.delete(targetMember) }
    }

    @Test
    fun `add expense to group links correctly`() = runTest(testDispatcher) {
        val groupId = 7L
        val amount = 123.45

        coEvery { groupDao.getById(groupId) } returns ExpenseGroup(
            id = groupId,
            name = "Flatmates",
            defaultCurrency = "USD"
        )
        coEvery {
            coordinator.addExpenseWithLink(
                groupId = groupId,
                systemExpenseId = 500L,
                description = "Groceries",
                amount = amount,
                paidById = 12L,
                currency = "USD",
                splitType = SplitType.EQUAL,
                customSplitsJson = null,
                date = 1_700_000_100_000L
            )
        } returns GroupExpenseCreationResult.Success(groupExpenseId = 300L, expenseId = 500L)

        val result = repository.addExpenseWithLink(
            groupId = groupId,
            systemExpenseId = 500L,
            description = "Groceries",
            amount = amount,
            paidById = 12L,
            splitType = SplitType.EQUAL,
            customSplitsJson = null,
            date = 1_700_000_100_000L
        )

        assertTrue(result is GroupExpenseCreationResult.Success)
        assertEquals(300L, (result as GroupExpenseCreationResult.Success).groupExpenseId)
        assertApproxEquals(123.45, amount, 0.0)
        coVerify(exactly = 1) {
            coordinator.addExpenseWithLink(
                groupId = groupId,
                systemExpenseId = 500L,
                description = "Groceries",
                amount = amount,
                paidById = 12L,
                currency = "USD",
                splitType = SplitType.EQUAL,
                customSplitsJson = null,
                date = 1_700_000_100_000L
            )
        }
    }

    @Test
    fun `get active groups with details returns populated data`() = runTest(testDispatcher) {
        val groupA = ExpenseGroup(id = 1L, name = "Trip", defaultCurrency = "EUR")
        val groupB = ExpenseGroup(id = 2L, name = "Home", defaultCurrency = "USD")

        coEvery { groupDao.getActive() } returns listOf(groupA, groupB)
        coEvery { memberDao.getAllForGroups(listOf(1L, 2L)) } returns listOf(
            GroupMember(id = 10L, groupId = 1L, name = "Alice"),
            GroupMember(id = 11L, groupId = 1L, name = "Bob"),
            GroupMember(id = 20L, groupId = 2L, name = "Chris")
        )
        coEvery { groupExpenseDao.getExpensesForGroups(listOf(1L, 2L)) } returns listOf(
            GroupExpense(
                id = 100L,
                groupId = 1L,
                expenseId = 1000L,
                paidById = 10L,
                date = 1_700_000_000_000L,
                description = "Lunch",
                totalAmount = 60.0,
                currency = "EUR",
                splitType = SplitType.EQUAL
            ),
            GroupExpense(
                id = 101L,
                groupId = 2L,
                expenseId = 1001L,
                paidById = 20L,
                date = 1_700_000_200_000L,
                description = "Utilities",
                totalAmount = 80.5,
                currency = "USD",
                splitType = SplitType.EQUAL
            )
        )

        val result = repository.getActiveGroupsWithDetails()

        assertEquals(2, result.size)
        val trip = result.first { it.group.id == 1L }
        val home = result.first { it.group.id == 2L }

        assertEquals(2, trip.members.size)
        assertEquals(1, trip.expenses.size)
        assertApproxEquals(60.0, trip.expenses.first().totalAmount, 0.0)

        assertEquals(1, home.members.size)
        assertEquals(1, home.expenses.size)
        assertApproxEquals(80.5, home.expenses.first().totalAmount, 0.0)
    }
}
