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
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.groups.GroupTransactionCoordinator
import com.yourname.expensetracker.domain.logic.CustomSplitJsonCodec
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class GroupsRepositoryImplTest {

    private val database = mockk<AppDatabase>(relaxed = true)
    private val groupDao = mockk<ExpenseGroupDao>(relaxed = true)
    private val memberDao = mockk<GroupMemberDao>(relaxed = true)
    private val groupExpenseDao = mockk<GroupExpenseDao>(relaxed = true)
    private val coordinator = mockk<GroupTransactionCoordinator>(relaxed = true)
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: GroupsRepositoryImpl

    @Before
    fun setUp() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }

        repository = GroupsRepositoryImpl(
            writeBarrier = writeBarrier,
            database = database,
            groupDao = groupDao,
            memberDao = memberDao,
            groupExpenseDao = groupExpenseDao,
            coordinator = coordinator,
            currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true),
            timeProvider = mockk<TimeProvider>(relaxed = true),
            ioDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `create group returns group with members`() = runTest(testDispatcher) {
        coEvery {
            coordinator.createGroupWithMembers(
                name = "Trip",
                description = "Summer trip",
                currency = "EUR",
                members = any(),
                onInsideTransaction = any()
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
                },
                onInsideTransaction = any()
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
        coVerify(exactly = 0) { memberDao.update(any()) }
    }

    @Test
    fun `member delete ignores equal split expenses before joinedAt`() = runTest(testDispatcher, timeout = 60.seconds) {
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
        coVerify(exactly = 1) { memberDao.update(match { it.id == targetMember.id && it.leftAt != null }) }
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
        coEvery { memberDao.getActiveMembersForGroup(groupId) } returns listOf(
            GroupMember(id = 12L, groupId = groupId, name = "Alice", isCurrentUser = true)
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

    @Test
    fun `both expense entry points reject mismatched active member payloads`() = runTest(testDispatcher) {
        val groupId = 7L
        val alice = GroupMember(id = 10L, groupId = groupId, name = "Alice", isCurrentUser = true)
        val bob = GroupMember(id = 20L, groupId = groupId, name = "Bob")
        val carol = GroupMember(id = 30L, groupId = groupId, name = "Carol", leftAt = 1_700_000_000_000L)
        coEvery { groupDao.getById(groupId) } returns ExpenseGroup(id = groupId, name = "Trip", defaultCurrency = "EUR")
        coEvery { memberDao.getActiveMembersForGroup(groupId) } returns listOf(alice, bob)

        listOf(SplitType.UNEQUAL, SplitType.CUSTOM_AMOUNT, SplitType.CUSTOM_PERCENT).forEach { splitType ->
            val fullShare = if (splitType == SplitType.CUSTOM_PERCENT) 100.0 else 90.0
            val aliceShare = if (splitType == SplitType.CUSTOM_PERCENT) 25.0 else 10.0
            val bobShare = fullShare - aliceShare
            val invalidPayloads = listOf(
                mapOf(alice.id to aliceShare, bob.id to bobShare, carol.id to 0.0),
                mapOf(alice.id to fullShare),
                mapOf(alice.id to aliceShare, 999L to bobShare)
            ).map(CustomSplitJsonCodec::toCanonicalJson)
            invalidPayloads.forEach { payload ->
                val createResult = repository.createSystemExpenseAndLinkToGroup(
                    groupId = groupId, description = "Dinner", amount = 90.0,
                    paidById = alice.id, currency = "EUR", splitType = splitType,
                    customSplitsJson = payload, date = 1_700_000_100_000L,
                    transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
                    notes = null
                )
                val addResult = repository.addExpenseWithLink(
                    groupId = groupId, systemExpenseId = 500L, description = "Dinner", amount = 90.0,
                    paidById = alice.id, splitType = splitType,
                    customSplitsJson = payload, date = 1_700_000_100_000L
                )
                assertTrue(createResult is GroupExpenseCreationResult.Error)
                assertTrue(addResult is GroupExpenseCreationResult.Error)
                assertEquals("Invalid custom split for active members", (createResult as GroupExpenseCreationResult.Error).message)
                assertEquals("Invalid custom split for active members", (addResult as GroupExpenseCreationResult.Error).message)
            }
        }

        coVerify(exactly = 0) {
            coordinator.createSystemExpenseAndLinkToGroup(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        }
        coVerify(exactly = 0) {
            coordinator.addExpenseWithLink(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `malformed null and invalid payer inputs return bounded errors`() = runTest(testDispatcher) {
        val groupId = 8L
        val alice = GroupMember(id = 10L, groupId = groupId, name = "Alice", isCurrentUser = true)
        coEvery { groupDao.getById(groupId) } returns ExpenseGroup(id = groupId, name = "Trip", defaultCurrency = "EUR")
        coEvery { memberDao.getActiveMembersForGroup(groupId) } returns listOf(alice)

        listOf<String?>(null, "", "not-json", "10:1", "{\"10\":1.001}",
            "{\"10\":-1}", "{\"10\":2}", "{\"10\":1e100}").forEach { payload ->
            val result = repository.createSystemExpenseAndLinkToGroup(
                groupId = groupId, description = "Dinner", amount = 1.0, paidById = alice.id,
                currency = "EUR", splitType = SplitType.CUSTOM_AMOUNT, customSplitsJson = payload,
                date = 1_700_000_100_000L
            )
            assertTrue(result is GroupExpenseCreationResult.Error)
            assertEquals("Invalid custom split for active members", (result as GroupExpenseCreationResult.Error).message)
        }
        val validJson = CustomSplitJsonCodec.toCanonicalJson(mapOf(alice.id to 1.0))
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 1.001, 1e100).forEach { invalidAmount ->
            val result = repository.addExpenseWithLink(
                groupId, 501L, "Dinner", invalidAmount, alice.id, SplitType.UNEQUAL,
                validJson, 1_700_000_100_000L
            )
            assertEquals("Invalid custom split for active members", (result as GroupExpenseCreationResult.Error).message)
        }
        val invalidPercent = repository.addExpenseWithLink(
            groupId, 501L, "Dinner", 1.0, alice.id, SplitType.CUSTOM_PERCENT,
            CustomSplitJsonCodec.toCanonicalJson(mapOf(alice.id to 99.99)), 1_700_000_100_000L
        )
        assertEquals("Invalid custom split for active members", (invalidPercent as GroupExpenseCreationResult.Error).message)

        val invalidPayer = repository.addExpenseWithLink(
            groupId = groupId, systemExpenseId = 501L, description = "Dinner", amount = 1.0,
            paidById = 999L, splitType = SplitType.EQUAL, date = 1_700_000_100_000L
        )
        assertTrue(invalidPayer is GroupExpenseCreationResult.Error)
        assertEquals("Payer is not an active member of this group", (invalidPayer as GroupExpenseCreationResult.Error).message)

        coEvery { memberDao.getActiveMembersForGroup(groupId) } returns emptyList()
        val emptyMembers = repository.createSystemExpenseAndLinkToGroup(
            groupId, "Dinner", 1.0, alice.id, "EUR", SplitType.EQUAL, null, 1_700_000_100_000L
        )
        assertTrue(emptyMembers is GroupExpenseCreationResult.Error)
        coVerify(exactly = 0) {
            coordinator.createSystemExpenseAndLinkToGroup(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            coordinator.addExpenseWithLink(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `valid split payloads preserve arguments and result propagation`() = runTest(testDispatcher) {
        val groupId = 9L
        val alice = GroupMember(id = 10L, groupId = groupId, name = "Alice", isCurrentUser = true)
        val bob = GroupMember(id = 20L, groupId = groupId, name = "Bob")
        coEvery { groupDao.getById(groupId) } returns ExpenseGroup(id = groupId, name = "Trip", defaultCurrency = "EUR")
        coEvery { memberDao.getActiveMembersForGroup(groupId) } returns listOf(alice, bob)
        val unequalJson = CustomSplitJsonCodec.toCanonicalJson(mapOf(alice.id to 10.0, bob.id to 80.0))
        val amountJson = unequalJson
        val percentJson = CustomSplitJsonCodec.toCanonicalJson(mapOf(alice.id to 25.0, bob.id to 75.0))
        coEvery { coordinator.createSystemExpenseAndLinkToGroup(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), null) } returns
            GroupExpenseCreationResult.Success(700L, 701L)
        coEvery { coordinator.addExpenseWithLink(any(), any(), any(), any(), any(), any(), any(), any(), any(), null) } returns
            GroupExpenseCreationResult.Success(702L, 701L)

        val createResult = repository.createSystemExpenseAndLinkToGroup(
            groupId, "Dinner", 90.0, alice.id, "EUR", SplitType.UNEQUAL, unequalJson,
            1_700_000_100_000L, com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE, "note"
        )
        val addResult = repository.addExpenseWithLink(
            groupId, 701L, "Dinner", 90.0, alice.id, SplitType.CUSTOM_AMOUNT, amountJson, 1_700_000_100_000L
        )
        val percentResult = repository.createSystemExpenseAndLinkToGroup(
            groupId, "Dinner", 100.0, alice.id, "EUR", SplitType.CUSTOM_PERCENT, percentJson,
            1_700_000_100_000L, com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE, null
        )

        assertEquals(GroupExpenseCreationResult.Success(700L, 701L), createResult)
        assertEquals(GroupExpenseCreationResult.Success(702L, 701L), addResult)
        assertEquals(GroupExpenseCreationResult.Success(700L, 701L), percentResult)
        val equalResult = repository.addExpenseWithLink(
            groupId, 701L, "Dinner", 90.0, alice.id, SplitType.EQUAL, null, 1_700_000_100_000L
        )
        assertEquals(GroupExpenseCreationResult.Success(702L, 701L), equalResult)
        coVerify(exactly = 1) {
            coordinator.createSystemExpenseAndLinkToGroup(
                groupId, "Dinner", 90.0, alice.id, "EUR", SplitType.UNEQUAL, unequalJson,
                1_700_000_100_000L, com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE, "note", null
            )
        }
        coVerify(exactly = 1) {
            coordinator.addExpenseWithLink(
                groupId, 701L, "Dinner", 90.0, alice.id, "EUR", SplitType.CUSTOM_AMOUNT,
                amountJson, 1_700_000_100_000L, null
            )
        }
    }

    @Test
    fun `history includes departed members and barrier blocks delegation`() = runTest(testDispatcher) {
        val groupId = 10L
        val alice = GroupMember(id = 10L, groupId = groupId, name = "Alice")
        val carol = GroupMember(id = 30L, groupId = groupId, name = "Carol", leftAt = 1_700_000_000_000L)
        val group = ExpenseGroup(id = groupId, name = "Trip", defaultCurrency = "EUR")
        coEvery { groupDao.getActive() } returns listOf(group)
        coEvery { memberDao.getAllForGroups(listOf(groupId)) } returns listOf(alice, carol)
        coEvery { groupExpenseDao.getExpensesForGroups(listOf(groupId)) } returns emptyList()
        assertEquals(listOf(alice, carol), repository.getActiveGroupsWithDetails().single().members)

        every { writeBarrier.checkWritesAllowed("GroupsRepositoryImpl.createSystemExpenseAndLinkToGroup") } throws
            IllegalStateException("blocked")
        try {
            repository.createSystemExpenseAndLinkToGroup(
                groupId, "Dinner", 90.0, alice.id, "EUR", SplitType.EQUAL, null, 1_700_000_100_000L
            )
            throw AssertionError("write barrier should reject")
        } catch (expected: IllegalStateException) {
            assertEquals("blocked", expected.message)
        }
        coVerify(exactly = 0) { memberDao.getActiveMembersForGroup(groupId) }
        coVerify(exactly = 0) {
            coordinator.createSystemExpenseAndLinkToGroup(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }
}
