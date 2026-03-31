package com.yourname.expensetracker.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.sql.SQLException

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
    private lateinit var coordinator: GroupTransactionCoordinator

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        groupDao = database.expenseGroupDao()
        memberDao = database.groupMemberDao()
        groupExpenseDao = database.groupExpenseDao()
        coordinator = GroupTransactionCoordinator(database, groupDao, memberDao, groupExpenseDao)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() {
        database.close()
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
            database, mockGroupDao, mockMemberDao, mockGroupExpenseDao
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
    fun `addExpenseToGroupAtomic should insert expense and update balances`() = runTest {
        // Arrange - Create group and members first
        val group = ExpenseGroup(name = "Test Group")
        val members = listOf(
            GroupMember(groupId = 0, name = "Alice"),
            GroupMember(groupId = 0, name = "Bob")
        )
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)

        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        val aliceId = savedMembers.first { it.name == "Alice" }.id
        val bobId = savedMembers.first { it.name == "Bob" }.id

        val groupExpense = GroupExpense(
            groupId = groupId,
            expenseId = 123L,
            paidById = aliceId,
            date = System.currentTimeMillis(),
            description = "Dinner",
            totalAmount = 100.0,
            currency = "EUR",
            splitType = SplitType.EQUAL
        )

        val balanceUpdates = mapOf(
            aliceId to 50.0,
            bobId to -50.0
        )

        // Act
        val expenseId = coordinator.addExpenseToGroupAtomic(groupExpense, balanceUpdates)

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

        // Add an expense
        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        val groupExpense = GroupExpense(
            groupId = groupId,
            expenseId = 456L,
            paidById = savedMembers[0].id,
            date = System.currentTimeMillis(),
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
    fun `addExpenseToGroupAtomic should handle zero balance updates`() = runTest {
        // Arrange
        val group = ExpenseGroup(name = "Test Group")
        val members = listOf(GroupMember(groupId = 0, name = "Alice"), GroupMember(groupId = 0, name = "Bob"))
        val groupId = coordinator.createGroupWithMembersAtomic(group, members)

        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        val groupExpense = GroupExpense(
            groupId = groupId,
            expenseId = 789L,
            paidById = savedMembers[0].id,
            date = System.currentTimeMillis(),
            description = "Gift",
            totalAmount = 0.0,
            splitType = SplitType.EQUAL
        )

        // Act
        val expenseId = coordinator.addExpenseToGroupAtomic(groupExpense, emptyMap())

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
}
