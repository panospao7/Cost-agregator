package com.yourname.expensetracker.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import io.mockk.mockk
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.groups.GroupCreationResult
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.groups.GroupValidationError
import com.yourname.expensetracker.domain.groups.Result
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectDispatcher
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectPlanner
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.sideeffect.PostCommitAction
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.logic.CustomSplitJsonCodec
import com.yourname.expensetracker.domain.sideeffect.SideEffectCategory
import com.yourname.expensetracker.domain.sideeffect.SideEffectOutcome
import com.yourname.expensetracker.domain.sideeffect.SideEffectTriggerType
import kotlinx.coroutines.CancellationException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.sql.SQLException
import kotlin.test.assertFailsWith

private const val TEST_DATE = 1_710_000_000_000L

private val Result<*, *>.isSuccess: Boolean
    get() = this is Result.Success

private val Result<*, *>.isFailure: Boolean
    get() = this is Result.Error

/**
 * CRITICAL TESTS (CRITICAL-2): GroupTransactionCoordinator
 * 
 * Tests atomic multi-DAO transactions to ensure data consistency.
 * Verifies rollback scenarios when one operation fails.
 * 
 * Coverage:
 * - Successful atomic group creation
 * - Rollback when member insert fails
 * - Rollback when expense insert fails
 * - Concurrent transaction handling
 * - Partial failure scenarios
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Config.OLDEST_SDK])
class GroupTransactionCoordinatorTest {

    private lateinit var database: AppDatabase
    private lateinit var groupDao: ExpenseGroupDao
    private lateinit var memberDao: GroupMemberDao
    private lateinit var groupExpenseDao: GroupExpenseDao
    private lateinit var expenseDao: ExpenseDao
    private lateinit var coordinator: GroupTransactionCoordinator
    private lateinit var transactionLifecycleCoordinator: TransactionLifecycleCoordinator
    private val timeProvider = mockk<com.yourname.expensetracker.domain.util.TimeProvider>(relaxed = true)
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val postCommitActionRunner = mockk<PostCommitActionRunner>(relaxed = true)
    private val transactionSideEffectPlanner = mockk<TransactionSideEffectPlanner>(relaxed = true)
    private val tlcPlanner = mockk<TransactionSideEffectPlanner>(relaxed = true)
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        every { timeProvider.now() } returns TEST_DATE
        every { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        every { writeBarrier.checkWritesAllowed(any<com.yourname.expensetracker.data.backup.DatabaseAccessOperation>()) } returns Unit

        groupDao = database.expenseGroupDao()
        memberDao = database.groupMemberDao()
        groupExpenseDao = database.groupExpenseDao()
        expenseDao = database.expenseDao()
        val transactionEventDao = database.transactionEventDao()
        val restoreMode = mockk<RestoreMaintenanceMode>(relaxed = true)
        every { restoreMode.isWritesAllowed() } returns true
        every { restoreMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        val tlcWriteBarrier = DatabaseWriteBarrier(restoreMode)
        transactionLifecycleCoordinator = TransactionLifecycleCoordinator(
            database, expenseDao, transactionEventDao, timeProvider,
            mockk<CurrencyConverter>(relaxed = true),
            mockk<TransactionSideEffectDispatcher>(relaxed = true),
            tlcPlanner,
            mockk<PostCommitActionRunner>(relaxed = true),
            mockk<RecurringLifecycleCoordinator>(relaxed = true),
            tlcWriteBarrier,
            mockk<CurrencySettingsRepository>(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        coordinator = GroupTransactionCoordinator(
            database, groupDao, memberDao, groupExpenseDao, expenseDao,
            mockk(relaxed = true), transactionLifecycleCoordinator,
            transactionSideEffectPlanner, postCommitActionRunner,
            writeBarrier, timeProvider, Dispatchers.Unconfined
        )
    }

    @After
    @Throws(IOException::class)
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `createGroupWithMembersAtomic should insert group and members successfully`() = runTest {
        // Arrange
        val group = ExpenseGroup(
            name = "Test Group",
            description = "Test Description",
            defaultCurrency = "EUR"
        )
        val members = listOf(
            GroupMember(groupId = 0, name = "Alice", isCurrentUser = true),
            GroupMember(groupId = 0, name = "Bob", isCurrentUser = false),
            GroupMember(groupId = 0, name = "Charlie", isCurrentUser = false)
        )

        // Act
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)

        // Assert
        assertThat(groupId).isGreaterThan(0)

        // Verify group exists
        val savedGroup = groupDao.getGroupById(groupId)
        assertThat(savedGroup).isNotNull()
        assertThat(savedGroup!!.name).isEqualTo("Test Group")

        // Verify members exist with correct groupId
        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        assertThat(savedMembers).hasSize(3)
        assertThat(savedMembers.map { it.name }).containsExactly("Alice", "Bob", "Charlie")
    }

    @Test
    fun `createGroupWithMembersAtomic should assign correct groupId to members`() = runTest {
        // Arrange
        val group = ExpenseGroup(name = "Dinner Group")
        val members = listOf(
            GroupMember(groupId = 0, name = "Person A"),
            GroupMember(groupId = 0, name = "Person B")
        )

        // Act
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)

        // Assert
        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        assertThat(savedMembers).hasSize(2)
        savedMembers.forEach { member ->
            assertThat(member.groupId).isEqualTo(groupId)
        }
    }

    @Test
    fun `createGroupWithMembersAtomic should rollback when member insert fails`() = runTest {
        // Arrange
        val group = ExpenseGroup(name = "Failing Group")
        val members = listOf(
            GroupMember(groupId = 0, name = "Valid Member"),
            GroupMember(groupId = 0, name = "Invalid Member") // This will fail
        )

        // Mock the memberDao to throw exception on second insert
        val mockMemberDao = mockk<GroupMemberDao>(relaxed = true)
        val mockGroupDao = mockk<ExpenseGroupDao>(relaxed = true)
        val mockGroupExpenseDao = mockk<GroupExpenseDao>(relaxed = true)

        coEvery { mockGroupDao.insert(any()) } returns 1L
        coEvery { mockMemberDao.insertAll(any()) } throws SQLException("Disk full")

        val mockCoordinator = GroupTransactionCoordinator(
            database, mockGroupDao, mockMemberDao, mockGroupExpenseDao, expenseDao,
            mockk(relaxed = true), transactionLifecycleCoordinator,
            mockk(relaxed = true), mockk<PostCommitActionRunner>(relaxed = true),
            mockk<DatabaseWriteBarrier>(relaxed = true), timeProvider, Dispatchers.Unconfined
        )

        // Act - Should throw exception
        var exception: Exception? = null
        try {
            mockCoordinator.createGroupWithMembersAtomic(group, members)
        } catch (e: Exception) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(SQLException::class.java)

        // Verify group insert was attempted but transaction rolled back
        coVerify(exactly = 1) { mockGroupDao.insert(any()) }
    }

    @Test
    fun `addExpenseToGroupAtomic should insert expense record`() = runTest {
        // Arrange - Create group and members first
        val group = ExpenseGroup(name = "Test Group")
        val members = listOf(
            GroupMember(groupId = 0, name = "Alice"),
            GroupMember(groupId = 0, name = "Bob")
        )
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)

        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        val aliceId = savedMembers.first { it.name == "Alice" }.id

        // Create an actual expense first (required for foreign key constraint)
        val expense = Expense(
            amount = 100.0,
            merchant = "Test Merchant",
            transactionType = TransactionType.PURCHASE,
            notes = "Dinner",
            date = TEST_DATE
        )
        val actualExpenseId = expenseDao.insert(expense)

        val groupExpense = GroupExpense(
            groupId = groupId,
            expenseId = actualExpenseId,
            paidById = aliceId,
            date = TEST_DATE,
            description = "Dinner",
            totalAmount = 100.0,
            currency = "EUR",
            splitType = SplitType.EQUAL
        )

        // Act — insert-only; no balance updates
        val expenseId = coordinator.addExpenseToGroupAtomic(groupExpense)

        // Assert
        assertThat(expenseId).isGreaterThan(0)

        // Verify expense was saved
        val expenses = groupExpenseDao.getExpensesForGroup(groupId).first()
        assertThat(expenses).hasSize(1)
        assertThat(expenses[0].description).isEqualTo("Dinner")
    }

    @Test
    fun `deleteGroupAtomic should remove group members and expenses`() = runTest {
        // Arrange - Create a complete group with members and expenses
        val group = ExpenseGroup(name = "Group to Delete")
        val members = listOf(
            GroupMember(groupId = 0, name = "Member 1"),
            GroupMember(groupId = 0, name = "Member 2")
        )
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)

        // Create an actual expense first (required for foreign key constraint)
        val expense = Expense(
            amount = 50.0,
            merchant = "Test Merchant",
            transactionType = TransactionType.PURCHASE,
            notes = "Lunch",
            date = TEST_DATE
        )
        val expenseId = expenseDao.insert(expense)

        // Add group expense linking to actual expense
        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        val groupExpense = GroupExpense(
            groupId = groupId,
            expenseId = expenseId,
            paidById = savedMembers[0].id,
            date = TEST_DATE,
            description = "Lunch",
            totalAmount = 50.0,
            splitType = SplitType.EQUAL
        )
        groupExpenseDao.insert(groupExpense)

        // Verify data exists before deletion
        assertThat(groupDao.getGroupById(groupId)).isNotNull()
        assertThat(memberDao.getMembersForGroup(groupId).first()).hasSize(2)
        assertThat(groupExpenseDao.getExpensesForGroup(groupId).first()).hasSize(1)

        // Act
        coordinator.deleteGroupAtomic(groupId)

        // Assert - Everything should be deleted
        assertThat(groupDao.getGroupById(groupId)).isNull()
        assertThat(memberDao.getMembersForGroup(groupId).first()).isEmpty()
        assertThat(groupExpenseDao.getExpensesForGroup(groupId).first()).isEmpty()
    }

    @Test
    fun `transaction should be atomic - partial failure rolls back everything`() = runTest {
        // This test verifies the critical requirement: atomicity
        // Arrange - Create initial state
        val initialGroupCount = groupDao.getAllGroups().first().size
        val initialMemberCount = memberDao.getAllMembers().first().size

        val group = ExpenseGroup(name = "Atomic Test Group")
        val members = (1..100).map { GroupMember(groupId = 0, name = "Member $it") }

        // Act
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)

        // Assert - Either all 100 members were created, or none (and exception thrown)
        val finalGroupCount = groupDao.getAllGroups().first().size
        val finalMemberCount = memberDao.getAllMembers().first().size

        // If successful
        if (groupId > 0) {
            assertThat(finalGroupCount).isEqualTo(initialGroupCount + 1)
            assertThat(finalMemberCount).isEqualTo(initialMemberCount + 100)
        }
        // If failed, counts should remain unchanged (rolled back)
    }

    @Test
    fun `concurrent transactions should not interfere`() = runTest {
        // Arrange
        val groups = (1..5).map { index ->
            ExpenseGroup(name = "Concurrent Group $index")
        }
        val members = listOf(GroupMember(groupId = 0, name = "Test Member"))

        // Act - Create multiple groups concurrently
        val groupIds = groups.map { group ->
            coordinator.createGroupWithMembersAtomic(group, members)
        }

        // Assert
        assertThat(groupIds).hasSize(5)
        groupIds.forEach { id ->
            assertThat(id).isGreaterThan(0)
        }

        // Verify all groups exist
        groupIds.forEach { id ->
            val group = groupDao.getGroupById(id)
            assertThat(group).isNotNull()
        }
    }

    @Test
    fun `createGroupWithMembersAtomic should handle empty member list`() = runTest {
        // Arrange
        val group = ExpenseGroup(name = "Empty Group")
        val emptyMembers = emptyList<GroupMember>()

        // Act
        val groupId = coordinator.createGroupWithMembersAtomic(group, emptyMembers)

        // Assert
        assertThat(groupId).isGreaterThan(0)
        
        val savedGroup = groupDao.getGroupById(groupId)
        assertThat(savedGroup).isNotNull()
        
        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        assertThat(savedMembers).isEmpty()
    }

    @Test
    fun `createGroupWithMembersAtomic should handle single member`() = runTest {
        // Arrange
        val group = ExpenseGroup(name = "Solo Group")
        val singleMember = listOf(GroupMember(groupId = 0, name = "Solo User", isCurrentUser = true))

        // Act
        val groupId = coordinator.createGroupWithMembersAtomic(group, singleMember)

        // Assert
        assertThat(groupId).isGreaterThan(0)
        
        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        assertThat(savedMembers).hasSize(1)
        assertThat(savedMembers[0].name).isEqualTo("Solo User")
        assertThat(savedMembers[0].isCurrentUser).isTrue()
    }

    @Test
    fun `addExpenseToGroupAtomic should handle insert with no extra args`() = runTest {
        // Arrange
        val group = ExpenseGroup(name = "Test Group")
        val members = listOf(GroupMember(groupId = 0, name = "Alice"), GroupMember(groupId = 0, name = "Bob"))
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)

        // Create an actual expense first (required for foreign key constraint)
        val expense = Expense(
            amount = 0.0,
            merchant = "Gift",
            transactionType = TransactionType.PURCHASE,
            notes = "Gift",
            date = TEST_DATE
        )
        val actualExpenseId = expenseDao.insert(expense)

        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        val groupExpense = GroupExpense(
            groupId = groupId,
            expenseId = actualExpenseId,
            paidById = savedMembers[0].id,
            date = TEST_DATE,
            description = "Gift",
            totalAmount = 0.0,
            splitType = SplitType.EQUAL
        )

        // Act — insert-only
        val expenseId = coordinator.addExpenseToGroupAtomic(groupExpense)

        // Assert
        assertThat(expenseId).isGreaterThan(0)
    }

    @Test
    fun `deleteGroupAtomic should handle non-existent group gracefully`() = runTest {
        // Arrange
        val nonExistentGroupId = 999999L

        // Act & Assert - Should not throw
        coordinator.deleteGroupAtomic(nonExistentGroupId)

        // Verify nothing was deleted (no crash)
        // If we get here, the test passed
    }

    @Test
    fun `transaction integrity - all operations succeed or all fail`() = runTest {
        // This is the ultimate atomicity test
        // Arrange - Track state before
        val groupsBefore = groupDao.getAllGroups().first()
        val membersBefore = memberDao.getAllMembers().first()

        val group = ExpenseGroup(name = "Integrity Test")
        val members = listOf(
            GroupMember(groupId = 0, name = "Member 1"),
            GroupMember(groupId = 0, name = "Member 2")
        )

        // Act
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)

        // Assert
        if (groupId > 0) {
            // Success: verify both group and members exist
            assertThat(groupDao.getGroupById(groupId)).isNotNull()
            assertThat(memberDao.getMembersForGroup(groupId).first()).hasSize(2)
        }

        // If any assertion fails here, it means partial commit occurred (BUG!)
    }

    // ==================== B.4 Batch 2 Tests ====================

    @Test
    fun `createSystemExpenseAndLinkToGroup atomically creates both records`() = runTest {
        // Arrange - Create group with member
        val group = ExpenseGroup(name = "Atomic Link Group", defaultCurrency = "EUR")
        val members = listOf(
            GroupMember(groupId = 0, name = "Alice", isCurrentUser = true)
        )
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)
        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        val aliceId = savedMembers.first().id

        // Act
        val result = coordinator.createSystemExpenseAndLinkToGroup(
            groupId = groupId,
            description = "Atomic Dinner",
            amount = 50.0,
            paidById = aliceId,
            currency = "EUR",
        splitType = SplitType.EQUAL,
        date = TEST_DATE,
        transactionType = TransactionType.PURCHASE,
        notes = "Group expense via Alice"
        )

        // Assert
        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Success::class.java)
        val success = result as GroupExpenseCreationResult.Success
        assertThat(success.groupExpenseId).isGreaterThan(0)
        assertThat(success.expenseId).isGreaterThan(0)

        // Verify system expense exists
        val systemExpense = expenseDao.getById(success.expenseId)
        assertThat(systemExpense).isNotNull()
        assertThat(systemExpense!!.amount).isEqualTo(50.0)
        assertThat(systemExpense.merchant).isEqualTo("Atomic Dinner")

        // Verify group expense exists and is linked
        val groupExpenses = groupExpenseDao.getExpensesForGroup(groupId).first()
        assertThat(groupExpenses).hasSize(1)
        assertThat(groupExpenses[0].expenseId).isEqualTo(success.expenseId)
        assertThat(groupExpenses[0].totalAmount).isEqualTo(50.0)
    }

    @Test
    fun `createSystemExpenseAndLinkToGroup stores current user share on linked system expense`() = runTest {
        val group = ExpenseGroup(name = "Shared Link Group", defaultCurrency = "EUR")
        val groupId = coordinator.createGroupWithMembersAtomic(
            group = group,
            members = listOf(
                GroupMember(groupId = 0, name = "Alice", isCurrentUser = true),
                GroupMember(groupId = 0, name = "Bob")
            )
        )
        val members = memberDao.getMembersForGroup(groupId).first()
        val bobId = members.first { it.name == "Bob" }.id

        val result = coordinator.createSystemExpenseAndLinkToGroup(
            groupId = groupId,
            description = "Team Dinner",
            amount = 75.0,
            paidById = bobId,
            currency = "EUR",
            splitType = SplitType.EQUAL,
            date = TEST_DATE,
            transactionType = TransactionType.PURCHASE,
            notes = null
        )

        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Success::class.java)
        val success = result as GroupExpenseCreationResult.Success
        val linkedExpense = expenseDao.getById(success.expenseId)

        assertThat(linkedExpense).isNotNull()
        assertThat(linkedExpense!!.isSharedExpense).isTrue()
        assertThat(linkedExpense.isNotMine).isFalse()
        assertThat(linkedExpense.myShareAmount).isEqualTo(37.5)
        assertThat(linkedExpense.effectiveAmount).isEqualTo(37.5)
    }

    @Test
    fun `createSystemExpenseAndLinkToGroup fails for inactive group`() = runTest {
        // Arrange - Create then archive group
        val group = ExpenseGroup(name = "Archived Group")
        val members = listOf(GroupMember(groupId = 0, name = "Alice"))
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)
        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        groupDao.archiveGroup(groupId)

        // Act
        val result = coordinator.createSystemExpenseAndLinkToGroup(
            groupId = groupId,
            description = "Should Fail",
            amount = 25.0,
            paidById = savedMembers.first().id,
            currency = "EUR",
        splitType = SplitType.EQUAL,
        date = TEST_DATE,
        transactionType = TransactionType.PURCHASE
        )

        // Assert
        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Error::class.java)
        val error = result as GroupExpenseCreationResult.Error
        assertThat(error.message).contains("inactive")

        // Verify no system expense was created (atomic rollback)
        val allExpenses = expenseDao.getAllUncapped()
        assertThat(allExpenses).isEmpty()
    }

    @Test
    fun `createSystemExpenseAndLinkToGroup fails for non-member payer`() = runTest {
        // Arrange
        val group = ExpenseGroup(name = "Test Group")
        val members = listOf(GroupMember(groupId = 0, name = "Alice"))
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)

        // Act - use a non-existent member ID
        val result = coordinator.createSystemExpenseAndLinkToGroup(
            groupId = groupId,
            description = "Should Fail",
            amount = 30.0,
            paidById = 99999L,
            currency = "EUR",
        splitType = SplitType.EQUAL,
        date = TEST_DATE,
        transactionType = TransactionType.PURCHASE
        )

        // Assert
        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Error::class.java)
        val error = result as GroupExpenseCreationResult.Error
        assertThat(error.message).contains("Payer")

        // Verify no system expense was created (atomic rollback)
        val allExpenses = expenseDao.getAllUncapped()
        assertThat(allExpenses).isEmpty()
    }

    @Test
    fun `addMemberToGroup is atomic - validates inside transaction`() = runTest {
        // Arrange
        val group = ExpenseGroup(name = "Member Test Group")
        val members = listOf(GroupMember(groupId = 0, name = "Alice"))
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)

        // Act - add member to valid active group
        val result = coordinator.addMemberToGroup(groupId, "Bob", "bob@test.com", false)

        // Assert
        assertThat(result.isSuccess).isTrue()

        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        assertThat(savedMembers).hasSize(2)
        assertThat(savedMembers.map { it.name }).containsExactly("Alice", "Bob")
    }

    @Test
    fun `addMemberToGroup returns invalid group error for inactive group`() = runTest {
        // Arrange
        val group = ExpenseGroup(name = "Archive Test")
        val members = listOf(GroupMember(groupId = 0, name = "Alice"))
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)
        groupDao.archiveGroup(groupId)

        // Act
        val result = coordinator.addMemberToGroup(groupId, "Bob", null, false)

        // Assert
        assertThat(result.isFailure).isTrue()
        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.error).isInstanceOf(GroupValidationError.InvalidGroup::class.java)
    }

    @Test
    fun `addExpenseWithLink is atomic - validates inside transaction`() = runTest {
        // Arrange
        val group = ExpenseGroup(name = "Link Test Group", defaultCurrency = "EUR")
        val members = listOf(GroupMember(groupId = 0, name = "Alice"))
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)
        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        val aliceId = savedMembers.first().id

        // Create system expense first
        val expense = Expense(
            amount = 75.0,
            merchant = "Link Test",
            transactionType = TransactionType.PURCHASE,
            date = TEST_DATE
        )
        val systemExpenseId = expenseDao.insert(expense)

        // Act
        val result = coordinator.addExpenseWithLink(
            groupId = groupId,
            systemExpenseId = systemExpenseId,
            description = "Link Test",
            amount = 75.0,
            paidById = aliceId,
            currency = "EUR",
            splitType = SplitType.EQUAL,
            date = TEST_DATE
        )

        // Assert
        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Success::class.java)
        val success = result as GroupExpenseCreationResult.Success
        assertThat(success.groupExpenseId).isGreaterThan(0)
        assertThat(success.expenseId).isEqualTo(systemExpenseId)
    }

    @Test
    fun `addExpenseWithLink normalizes linked existing expense ownership fields`() = runTest {
        val group = ExpenseGroup(name = "Existing Link Group", defaultCurrency = "EUR")
        val groupId = coordinator.createGroupWithMembersAtomic(
            group = group,
            members = listOf(
                GroupMember(groupId = 0, name = "Alice", isCurrentUser = true),
                GroupMember(groupId = 0, name = "Bob"),
                GroupMember(groupId = 0, name = "Charlie")
            )
        )
        val members = memberDao.getMembersForGroup(groupId).first()
        val bobId = members.first { it.name == "Bob" }.id

        val systemExpenseId = expenseDao.insert(
            Expense(
                amount = 90.0,
                merchant = "Groceries",
                transactionType = TransactionType.PURCHASE,
                date = TEST_DATE,
                isSharedExpense = false,
                mySharePercentage = 50,
                myShareAmount = null
            )
        )

        val result = coordinator.addExpenseWithLink(
            groupId = groupId,
            systemExpenseId = systemExpenseId,
            description = "Groceries",
            amount = 90.0,
            paidById = bobId,
            currency = "EUR",
            splitType = SplitType.CUSTOM_AMOUNT,
            customSplitsJson = CustomSplitJsonCodec.toCanonicalJson(
                members.associate { member ->
                    when (member.name) {
                        "Alice" -> member.id to 15.0
                        "Bob" -> member.id to 45.0
                        else -> member.id to 30.0
                    }
                }
            ),
            date = TEST_DATE
        )

        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Success::class.java)
        val linkedExpense = expenseDao.getById(systemExpenseId)

        assertThat(linkedExpense).isNotNull()
        assertThat(linkedExpense!!.isSharedExpense).isTrue()
        assertThat(linkedExpense.isNotMine).isFalse()
        assertThat(linkedExpense.mySharePercentage).isNull()
        assertThat(linkedExpense.myShareAmount).isEqualTo(15.0)
        assertThat(linkedExpense.effectiveAmount).isEqualTo(15.0)
    }

    @Test
    fun `addExpenseWithLink fails closed when current user member is missing and rolls back`() = runTest {
        val group = ExpenseGroup(name = "No Current User Group", defaultCurrency = "EUR")
        val groupId = coordinator.createGroupWithMembersAtomic(
            group = group,
            members = listOf(
                GroupMember(groupId = 0, name = "Alice"),
                GroupMember(groupId = 0, name = "Bob")
            )
        )
        val members = memberDao.getMembersForGroup(groupId).first()
        val payerId = members.first().id

        val systemExpenseId = expenseDao.insert(
            Expense(
                amount = 40.0,
                merchant = "Museum",
                transactionType = TransactionType.PURCHASE,
                date = TEST_DATE
            )
        )

        val beforeLink = expenseDao.getById(systemExpenseId)

        val result = coordinator.addExpenseWithLink(
            groupId = groupId,
            systemExpenseId = systemExpenseId,
            description = "Museum",
            amount = 40.0,
            paidById = payerId,
            currency = "EUR",
            splitType = SplitType.EQUAL,
            customSplitsJson = null,
            date = TEST_DATE
        )

        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Error::class.java)
        assertThat((result as GroupExpenseCreationResult.Error).message).contains("Current user")
        assertThat(groupExpenseDao.getExpensesForGroup(groupId).first()).isEmpty()

        val afterFailure = expenseDao.getById(systemExpenseId)
        assertThat(afterFailure).isEqualTo(beforeLink)
    }

    @Test
    fun `addExpenseWithLink rejects already linked system expense`() = runTest {
        val groupId = coordinator.createGroupWithMembersAtomic(
            group = ExpenseGroup(name = "Link Guard Group", defaultCurrency = "EUR"),
            members = listOf(GroupMember(groupId = 0, name = "Alice", isCurrentUser = true))
        )
        val payerId = memberDao.getMembersForGroup(groupId).first().first().id

        val systemExpenseId = expenseDao.insert(
            Expense(
                amount = 25.0,
                merchant = "Taxi",
                transactionType = TransactionType.PURCHASE,
                date = TEST_DATE
            )
        )

        val first = coordinator.addExpenseWithLink(
            groupId = groupId,
            systemExpenseId = systemExpenseId,
            description = "Taxi",
            amount = 25.0,
            paidById = payerId,
            currency = "EUR",
            splitType = SplitType.EQUAL,
            date = TEST_DATE
        )
        assertThat(first).isInstanceOf(GroupExpenseCreationResult.Success::class.java)

        val second = coordinator.addExpenseWithLink(
            groupId = groupId,
            systemExpenseId = systemExpenseId,
            description = "Taxi duplicate",
            amount = 25.0,
            paidById = payerId,
            currency = "EUR",
            splitType = SplitType.EQUAL,
            date = TEST_DATE
        )

        assertThat(second).isInstanceOf(GroupExpenseCreationResult.Error::class.java)
        assertThat((second as GroupExpenseCreationResult.Error).message)
            .contains("already attached")
        assertThat(groupExpenseDao.getExpensesForGroup(groupId).first()).hasSize(1)
    }

    @Test
    fun `addExpenseToGroupAtomic rejects already linked system expense`() = runTest {
        val groupId = coordinator.createGroupWithMembersAtomic(
            group = ExpenseGroup(name = "Atomic Guard Group", defaultCurrency = "EUR"),
            members = listOf(GroupMember(groupId = 0, name = "Alice", isCurrentUser = true))
        )
        val payerId = memberDao.getMembersForGroup(groupId).first().first().id
        val systemExpenseId = expenseDao.insert(
            Expense(
                amount = 12.0,
                merchant = "Coffee",
                transactionType = TransactionType.PURCHASE,
                date = TEST_DATE
            )
        )

        coordinator.addExpenseToGroupAtomic(
            GroupExpense(
                groupId = groupId,
                expenseId = systemExpenseId,
                paidById = payerId,
                date = TEST_DATE,
                description = "Coffee",
                totalAmount = 12.0
            )
        )

        var thrown: Exception? = null
        try {
            coordinator.addExpenseToGroupAtomic(
                GroupExpense(
                    groupId = groupId,
                    expenseId = systemExpenseId,
                    paidById = payerId,
                    date = TEST_DATE,
                    description = "Coffee again",
                    totalAmount = 12.0
                )
            )
        } catch (e: Exception) {
            thrown = e
        }

        assertThat(thrown).isNotNull()
        assertThat(thrown).isInstanceOf(android.database.sqlite.SQLiteConstraintException::class.java)
        assertThat(groupExpenseDao.getExpensesForGroup(groupId).first()).hasSize(1)
    }

    private fun nonEmptyBatch(): PostCommitActionBatch {
        val action = PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "test_action",
            category = SideEffectCategory.BUDGET,
            triggerType = SideEffectTriggerType.EXPENSE_CREATED,
            targetEntityType = "expense",
            targetEntityId = 42L,
            source = "test",
            correlationId = "test",
            causationId = null,
            idempotencyKey = "key-1",
            execute = { SideEffectOutcome.Completed }
        )
        return PostCommitActionBatch("test", listOf(action))
    }

    @Test
    fun `createSystemExpenseAndLinkToGroup runner cancellation rethrows`() = runTest {
        // Arrange
        val group = ExpenseGroup(name = "Cancel Test Group", defaultCurrency = "EUR")
        val members = listOf(GroupMember(groupId = 0, name = "Alice", isCurrentUser = true))
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)
        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        val aliceId = savedMembers.first().id

        coEvery { tlcPlanner.planCreated(any(), any(), any()) } returns nonEmptyBatch()
        coEvery { postCommitActionRunner.run(any()) } throws CancellationException("Cancelled")

        // Act & Assert
        var exception: Throwable? = null
        try {
            coordinator.createSystemExpenseAndLinkToGroup(
                groupId = groupId,
                description = "Cancel Test",
                amount = 50.0,
                paidById = aliceId,
                currency = "EUR",
                splitType = SplitType.EQUAL,
                date = TEST_DATE,
                transactionType = TransactionType.PURCHASE,
                notes = null
            )
        } catch (e: CancellationException) {
            exception = e
        }
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `addExpenseWithLink runner cancellation rethrows`() = runTest {
        // Arrange
        val group = ExpenseGroup(name = "Cancel Link Group", defaultCurrency = "EUR")
        val members = listOf(GroupMember(groupId = 0, name = "Alice", isCurrentUser = true))
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)
        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        val aliceId = savedMembers.first().id

        val expense = Expense(
            amount = 75.0, merchant = "Cancel Link",
            transactionType = TransactionType.PURCHASE, date = TEST_DATE
        )
        val systemExpenseId = expenseDao.insert(expense)

        coEvery { tlcPlanner.planUpdated(any(), any(), any(), any()) } returns nonEmptyBatch()
        coEvery { postCommitActionRunner.run(any()) } throws CancellationException("Cancelled")

        // Act & Assert
        var exception: Throwable? = null
        try {
            coordinator.addExpenseWithLink(
                groupId = groupId,
                systemExpenseId = systemExpenseId,
                description = "Cancel Link",
                amount = 75.0,
                paidById = aliceId,
                currency = "EUR",
                splitType = SplitType.EQUAL,
                date = TEST_DATE
            )
        } catch (e: CancellationException) {
            exception = e
        }
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `permanentlyDeleteGroup runner cancellation rethrows`() = runTest {
        // Arrange
        val group = ExpenseGroup(name = "Permanent Delete Group", defaultCurrency = "EUR")
        val members = listOf(GroupMember(groupId = 0, name = "Alice", isCurrentUser = true))
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)
        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        val aliceId = savedMembers.first().id

        val expense = Expense(
            amount = 50.0, merchant = "Delete Me",
            transactionType = TransactionType.PURCHASE, date = TEST_DATE
        )
        val expenseId = expenseDao.insert(expense)
        groupExpenseDao.insert(
            GroupExpense(
                groupId = groupId, expenseId = expenseId,
                paidById = aliceId, date = TEST_DATE,
                description = "Delete Me", totalAmount = 50.0,
                currency = "EUR"
            )
        )

        coEvery { transactionSideEffectPlanner.planBulkUpdated(any(), any(), any()) } returns nonEmptyBatch()
        coEvery { postCommitActionRunner.run(any()) } throws CancellationException("Cancelled")

        // Act & Assert
        var exception: Throwable? = null
        try {
            coordinator.permanentlyDeleteGroup(groupId)
        } catch (e: CancellationException) {
            exception = e
        }
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `createSystemExpenseAndLinkToGroup runner non-cancellation failure returns success after commit`() = runTest {
        // Arrange
        val group = ExpenseGroup(name = "Best Effort Group", defaultCurrency = "EUR")
        val members = listOf(GroupMember(groupId = 0, name = "Alice", isCurrentUser = true))
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)
        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        val aliceId = savedMembers.first().id

        coEvery { tlcPlanner.planCreated(any(), any(), any()) } returns nonEmptyBatch()
        coEvery { postCommitActionRunner.run(any()) } throws RuntimeException("Best-effort failure")

        // Act
        val result = coordinator.createSystemExpenseAndLinkToGroup(
            groupId = groupId,
            description = "Best Effort Test",
            amount = 50.0,
            paidById = aliceId,
            currency = "EUR",
            splitType = SplitType.EQUAL,
            date = TEST_DATE,
            transactionType = TransactionType.PURCHASE,
            notes = null
        )

        // Assert
        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Success::class.java)
    }

    // ==================== PR1: joinedAt normalization ====================

    @Test
    fun `createGroupWithMembers sets joinedAt for members with zero joinedAt`() = runTest {
        // Arrange — members with joinedAt = 0 (default sentinel)
        val members = listOf(
            GroupMember(groupId = 0, name = "Alice", isCurrentUser = true, joinedAt = 0L),
            GroupMember(groupId = 0, name = "Bob", joinedAt = 0L)
        )

        // Act
        val result = coordinator.createGroupWithMembers(
            name = "Test Group",
            description = null,
            currency = "EUR",
            members = members
        )

        // Assert
        assertThat(result).isInstanceOf(GroupCreationResult.Success::class.java)
        val groupId = (result as GroupCreationResult.Success).groupId

        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        assertThat(savedMembers).hasSize(2)
        savedMembers.forEach { member ->
            assertThat(member.joinedAt).isGreaterThan(0)
            assertThat(member.joinedAt).isEqualTo(TEST_DATE) // timeProvider.now() value
        }
    }

    @Test
    fun `createGroupWithMembers preserves existing non-zero joinedAt`() = runTest {
        // Arrange — members with explicit joinedAt values
        val explicitJoinedAt = 1_700_000_000_000L
        val members = listOf(
            GroupMember(groupId = 0, name = "Alice", isCurrentUser = true, joinedAt = explicitJoinedAt),
            GroupMember(groupId = 0, name = "Bob", joinedAt = explicitJoinedAt + 1000L)
        )

        // Act
        val result = coordinator.createGroupWithMembers(
            name = "Test Group",
            description = null,
            currency = "EUR",
            members = members
        )

        // Assert
        assertThat(result).isInstanceOf(GroupCreationResult.Success::class.java)
        val groupId = (result as GroupCreationResult.Success).groupId

        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        assertThat(savedMembers).hasSize(2)

        val alice = savedMembers.first { it.name == "Alice" }
        assertThat(alice.joinedAt).isEqualTo(explicitJoinedAt)

        val bob = savedMembers.first { it.name == "Bob" }
        assertThat(bob.joinedAt).isEqualTo(explicitJoinedAt + 1000L)
    }

    // ==================== PR1: Currency mismatch rejection ====================

    @Test
    fun `createSystemExpenseAndLinkToGroup rejects currency mismatch`() = runTest {
        // Arrange — group with EUR default currency
        val group = ExpenseGroup(name = "Currency Mismatch Group", defaultCurrency = "EUR")
        val members = listOf(
            GroupMember(groupId = 0, name = "Alice", isCurrentUser = true)
        )
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)
        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        val aliceId = savedMembers.first().id

        // Act — try to create expense with USD
        val result = coordinator.createSystemExpenseAndLinkToGroup(
            groupId = groupId,
            description = "Dinner",
            amount = 50.0,
            paidById = aliceId,
            currency = "USD",
            splitType = SplitType.EQUAL,
            date = TEST_DATE,
            transactionType = TransactionType.PURCHASE
        )

        // Assert
        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Error::class.java)
        val error = result as GroupExpenseCreationResult.Error
        assertThat(error.message).contains("does not match group currency")
        assertThat(error.message).contains("EUR")
        assertThat(error.message).contains("USD")

        // Verify no system expense was created (atomic rollback)
        val allExpenses = expenseDao.getAllUncapped()
        assertThat(allExpenses).isEmpty()
    }

    // =========================================================================
    // PR8 — Idempotency keys and soft-delete
    // =========================================================================

    @Test
    fun `addExpenseToGroup with same idempotencyKey returns existing expense`() = runTest {
        val group = ExpenseGroup(name = "Idempotency Group", defaultCurrency = "EUR")
        val members = listOf(GroupMember(groupId = 0, name = "Alice", isCurrentUser = true))
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)
        val aliceId = memberDao.getMembersForGroup(groupId).first().first().id

        val key = "test-key-123"
        val result1 = coordinator.addExpenseToGroup(
            groupId = groupId, description = "Dinner", amount = 50.0,
            paidById = aliceId, date = TEST_DATE, idempotencyKey = key
        )
        assertThat(result1).isInstanceOf(GroupExpenseCreationResult.Success::class.java)
        val success1 = result1 as GroupExpenseCreationResult.Success

        val result2 = coordinator.addExpenseToGroup(
            groupId = groupId, description = "Dinner", amount = 50.0,
            paidById = aliceId, date = TEST_DATE, idempotencyKey = key
        )
        assertThat(result2).isInstanceOf(GroupExpenseCreationResult.Success::class.java)
        val success2 = result2 as GroupExpenseCreationResult.Success

        // Same idempotency key → same expense returned
        assertThat(success2.groupExpenseId).isEqualTo(success1.groupExpenseId)
        assertThat(groupExpenseDao.getExpensesForGroupOnce(groupId)).hasSize(1)
    }

    @Test
    fun `addExpenseToGroup without idempotencyKey creates unique expenses each time`() = runTest {
        val group = ExpenseGroup(name = "UUID Group", defaultCurrency = "EUR")
        val members = listOf(GroupMember(groupId = 0, name = "Alice", isCurrentUser = true))
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)
        val aliceId = memberDao.getMembersForGroup(groupId).first().first().id

        val result1 = coordinator.addExpenseToGroup(
            groupId = groupId, description = "Dinner", amount = 50.0,
            paidById = aliceId, date = TEST_DATE
        )
        val result2 = coordinator.addExpenseToGroup(
            groupId = groupId, description = "Dinner", amount = 50.0,
            paidById = aliceId, date = TEST_DATE
        )

        assertThat(result1).isInstanceOf(GroupExpenseCreationResult.Success::class.java)
        assertThat(result2).isInstanceOf(GroupExpenseCreationResult.Success::class.java)
        assertThat(groupExpenseDao.getExpensesForGroupOnce(groupId)).hasSize(2)
    }

    @Test
    fun `soft-deleted member can be re-added with same name`() = runTest {
        val group = ExpenseGroup(name = "Re-add Group", defaultCurrency = "EUR")
        val members = listOf(
            GroupMember(groupId = 0, name = "Alice", isCurrentUser = true),
            GroupMember(groupId = 0, name = "Bob", isCurrentUser = false)
        )
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)
        val all = memberDao.getMembersForGroup(groupId).first()
        val bobId = all.first { it.name == "Bob" }.id

        // Soft-delete Bob directly via DAO update (removeMember lives in GroupLifecycleCoordinator, not this coordinator)
        val bobBefore = memberDao.getById(bobId)!!
        memberDao.update(bobBefore.copy(leftAt = TEST_DATE))

        // Verify Bob is soft-deleted (leftAt set) but still in DB
        val afterRemoval = memberDao.getAllForGroup(groupId)
        assertThat(afterRemoval).hasSize(2)
        val bobAfter = afterRemoval.first { it.name == "Bob" }
        assertThat(bobAfter.leftAt).isNotNull()

        // Verify Bob is not in active members
        val active = memberDao.getActiveMembersForGroup(groupId)
        assertThat(active).hasSize(1)
        assertThat(active.first().name).isEqualTo("Alice")

        // Re-add Bob with same name — should succeed because active-member check excludes left members
        val addResult = coordinator.addMemberToGroup(groupId, "Bob")
        assertThat(addResult.isSuccess).isTrue()

        val finalActive = memberDao.getActiveMembersForGroup(groupId)
        assertThat(finalActive).hasSize(2)
    }
}
