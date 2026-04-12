package com.yourname.expensetracker.data.database.dao

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupMemberDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var expenseGroupDao: ExpenseGroupDao
    private lateinit var groupMemberDao: GroupMemberDao
    private lateinit var groupExpenseDao: GroupExpenseDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()

        expenseGroupDao = database.expenseGroupDao()
        groupMemberDao = database.groupMemberDao()
        groupExpenseDao = database.groupExpenseDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun insertGroup(name: String = "Group A"): Long {
        return expenseGroupDao.insert(
            ExpenseGroup(name = name, description = "Test group")
        )
    }

    private fun makeMember(
        groupId: Long,
        name: String,
        email: String? = null,
        isCurrentUser: Boolean = false
    ) = GroupMember(
        groupId = groupId,
        name = name,
        email = email,
        isCurrentUser = isCurrentUser
    )

    @Test
    fun insertMember_retrieveById() = runBlocking {
        val groupId = insertGroup()
        val memberId = groupMemberDao.insert(
            makeMember(groupId = groupId, name = "Alice", email = "alice@test.com")
        )

        assertTrue(memberId > 0)

        val loaded = groupMemberDao.getById(memberId)
        assertNotNull(loaded)
        assertEquals(memberId, loaded!!.id)
        assertEquals(groupId, loaded.groupId)
        assertEquals("Alice", loaded.name)
        assertEquals("alice@test.com", loaded.email)
    }

    @Test
    fun queryMembersByGroupId_returnsOnlyMatchingGroup() = runBlocking {
        val groupA = insertGroup("Group A")
        val groupB = insertGroup("Group B")

        groupMemberDao.insert(makeMember(groupA, "Charlie"))
        groupMemberDao.insert(makeMember(groupA, "Alice"))
        groupMemberDao.insert(makeMember(groupB, "Bob"))

        val groupAMembers = groupMemberDao.getAllForGroup(groupA)

        assertEquals(2, groupAMembers.size)
        assertTrue(groupAMembers.all { it.groupId == groupA })
        // DAO query orders by name
        assertEquals(listOf("Alice", "Charlie"), groupAMembers.map { it.name })
    }

    @Test
    fun updateMember_persistsUpdatedFields() = runBlocking {
        val groupId = insertGroup()
        val memberId = groupMemberDao.insert(
            makeMember(groupId = groupId, name = "Alice", email = "a@old.com", isCurrentUser = false)
        )

        val original = groupMemberDao.getById(memberId)
        assertNotNull(original)

        val updated = original!!.copy(
            name = "Alice Cooper",
            email = "a@new.com",
            isCurrentUser = true
        )
        groupMemberDao.update(updated)

        val reloaded = groupMemberDao.getById(memberId)
        assertNotNull(reloaded)
        assertEquals("Alice Cooper", reloaded!!.name)
        assertEquals("a@new.com", reloaded.email)
        assertTrue(reloaded.isCurrentUser)
    }

    @Test
    fun deleteMemberWithExpenses_isRestrictedByForeignKey() = runBlocking {
        val groupId = insertGroup("Trip")
        val memberId = groupMemberDao.insert(makeMember(groupId, "Payer"))

        val groupExpenseId = groupExpenseDao.insert(
            GroupExpense(
                groupId = groupId,
                expenseId = null,
                paidById = memberId,
                date = 1_700_000_000_000L,
                description = "Dinner",
                totalAmount = 42.0
            )
        )
        assertTrue(groupExpenseId > 0)

        val member = groupMemberDao.getById(memberId)
        assertNotNull(member)

        var failedAsExpected = false
        try {
            groupMemberDao.delete(member!!)
        } catch (_: Exception) {
            failedAsExpected = true
        }

        assertTrue("Deleting referenced member should fail due to FK RESTRICT", failedAsExpected)
        assertNotNull(groupMemberDao.getById(memberId))
    }

    // ── Current-user invariant tests ────────────────────────────────────────────

    @Test
    fun setCurrentUser_promotesNewMember() = runBlocking {
        val groupId = insertGroup()
        val aliceId = groupMemberDao.insert(makeMember(groupId, "Alice"))
        val bobId = groupMemberDao.insert(makeMember(groupId, "Bob"))

        // Initially no current user.
        assertNull(groupMemberDao.getCurrentUser(groupId))

        groupMemberDao.setCurrentUser(groupId, aliceId)

        val current = groupMemberDao.getCurrentUser(groupId)
        assertNotNull(current)
        assertEquals(aliceId, current!!.id)
        assertTrue(current.isCurrentUser)
    }

    @Test
    fun setCurrentUser_switchesBetweenMembers() = runBlocking {
        val groupId = insertGroup()
        val aliceId = groupMemberDao.insert(makeMember(groupId, "Alice"))
        val bobId = groupMemberDao.insert(makeMember(groupId, "Bob"))

        groupMemberDao.setCurrentUser(groupId, aliceId)
        assertEquals(aliceId, groupMemberDao.getCurrentUser(groupId)!!.id)

        // Switch to Bob — Alice should be cleared.
        groupMemberDao.setCurrentUser(groupId, bobId)
        val current = groupMemberDao.getCurrentUser(groupId)
        assertNotNull(current)
        assertEquals(bobId, current!!.id)

        // Verify Alice is no longer current.
        val alice = groupMemberDao.getById(aliceId)
        assertNotNull(alice)
        assertFalse(alice!!.isCurrentUser)
    }

    @Test
    fun setCurrentUser_isolatedPerGroup() = runBlocking {
        val groupA = insertGroup("Group A")
        val groupB = insertGroup("Group B")
        val aliceInA = groupMemberDao.insert(makeMember(groupA, "Alice"))
        val bobInB = groupMemberDao.insert(makeMember(groupB, "Bob"))

        groupMemberDao.setCurrentUser(groupA, aliceInA)
        groupMemberDao.setCurrentUser(groupB, bobInB)

        // Both should be current in their respective groups.
        assertEquals(aliceInA, groupMemberDao.getCurrentUser(groupA)!!.id)
        assertEquals(bobInB, groupMemberDao.getCurrentUser(groupB)!!.id)
    }

    /**
     * Passing a memberId that belongs to a different group must NOT silently
     * promote that member. The operation should throw and leave both groups
     * untouched (apart from the clear step on the target group, which is safe
     * because only the caller's group is affected).
     */
    @Test
    fun setCurrentUser_rejectsCrossGroupMemberId() = runBlocking {
        val groupA = insertGroup("Group A")
        val groupB = insertGroup("Group B")

        val aliceInA = groupMemberDao.insert(makeMember(groupA, "Alice"))
        val bobInB   = groupMemberDao.insert(makeMember(groupB, "Bob"))

        // Set Bob as current user of Group B first.
        groupMemberDao.setCurrentUser(groupB, bobInB)
        assertEquals(bobInB, groupMemberDao.getCurrentUser(groupB)!!.id)

        // Attempt to promote Bob (who belongs to Group B) as current user of Group A.
        var threw = false
        try {
            groupMemberDao.setCurrentUser(groupA, bobInB)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(
            "setCurrentUser must reject a memberId that does not belong to the target group",
            threw
        )

        // Group A must have no current user — no silent cross-group mutation.
        assertNull(groupMemberDao.getCurrentUser(groupA))

        // Group B's current user must be unaffected by the failed call on Group A.
        val bobReloaded = groupMemberDao.getById(bobInB)
        assertNotNull(bobReloaded)
        assertTrue("Bob should still be current user in Group B", bobReloaded!!.isCurrentUser)
    }

    @Test
    fun clearCurrentUser_removesCurrentUserFlag() = runBlocking {
        val groupId = insertGroup()
        val aliceId = groupMemberDao.insert(makeMember(groupId, "Alice", isCurrentUser = true))

        assertNotNull(groupMemberDao.getCurrentUser(groupId))

        groupMemberDao.clearCurrentUser(groupId)

        assertNull(groupMemberDao.getCurrentUser(groupId))
        // Alice row still exists, just not current.
        val alice = groupMemberDao.getById(aliceId)
        assertNotNull(alice)
        assertFalse(alice!!.isCurrentUser)
    }

    // ── Fresh-install DB-level constraint tests (Batch 3) ───────────────────────

    /**
     * On a brand-new v71 database the partial unique index
     * `index_group_members_groupId_currentUser` must reject inserting a second
     * member with `isCurrentUser = 1` in the same group.
     */
    @Test
    fun freshInstall_rejectsDuplicateCurrentUserInSameGroup() = runBlocking {
        val groupId = insertGroup()

        // First current user — should succeed.
        groupMemberDao.insert(makeMember(groupId, "Alice", isCurrentUser = true))

        // Second current user in the same group — must be rejected at DB level.
        var rejected = false
        try {
            groupMemberDao.insert(makeMember(groupId, "Bob", isCurrentUser = true))
        } catch (_: SQLiteConstraintException) {
            rejected = true
        }
        assertTrue(
            "Fresh-install DB must reject a second isCurrentUser=1 in the same group",
            rejected
        )
    }

    /**
     * The constraint applies per group — different groups may each have their own
     * current user without conflict.
     */
    @Test
    fun freshInstall_allowsCurrentUserInDifferentGroups() = runBlocking {
        val groupA = insertGroup("Group A")
        val groupB = insertGroup("Group B")

        val aliceId = groupMemberDao.insert(makeMember(groupA, "Alice", isCurrentUser = true))
        val bobId = groupMemberDao.insert(makeMember(groupB, "Bob", isCurrentUser = true))

        assertTrue(aliceId > 0)
        assertTrue(bobId > 0)
    }

    /**
     * Non-current users are unconstrained — the partial index only covers
     * `isCurrentUser = 1` rows.
     */
    @Test
    fun freshInstall_allowsMultipleNonCurrentUsersInSameGroup() = runBlocking {
        val groupId = insertGroup()

        groupMemberDao.insert(makeMember(groupId, "Alice", isCurrentUser = false))
        groupMemberDao.insert(makeMember(groupId, "Bob", isCurrentUser = false))
        groupMemberDao.insert(makeMember(groupId, "Charlie", isCurrentUser = false))

        assertEquals(3, groupMemberDao.getMemberCount(groupId))
    }

    /**
     * On a brand-new v71 database the partial unique index
     * `index_group_expenses_expenseId_unique` must reject inserting a second
     * group_expense row with the same non-null `expenseId`.
     */
    @Test
    fun freshInstall_rejectsDuplicateNonNullExpenseIdInGroupExpenses() = runBlocking {
        val groupId = insertGroup()
        val memberId = groupMemberDao.insert(makeMember(groupId, "Payer"))

        // Create a real expense for the FK reference.
        val expenseId = database.expenseDao().insert(
            Expense(
                amount = 50.0,
                merchant = "Restaurant",
                transactionType = TransactionType.PURCHASE,
                date = 1_700_000_000_000L
            )
        )

        // First link — should succeed.
        groupExpenseDao.insert(
            GroupExpense(
                groupId = groupId,
                expenseId = expenseId,
                paidById = memberId,
                date = 1_700_000_000_000L,
                description = "First link",
                totalAmount = 50.0
            )
        )

        // Second link to the same expenseId — must be rejected at DB level.
        var rejected = false
        try {
            groupExpenseDao.insert(
                GroupExpense(
                    groupId = groupId,
                    expenseId = expenseId,
                    paidById = memberId,
                    date = 1_700_000_000_000L,
                    description = "Duplicate link",
                    totalAmount = 50.0
                )
            )
        } catch (_: SQLiteConstraintException) {
            rejected = true
        }
        assertTrue(
            "Fresh-install DB must reject duplicate non-null expenseId in group_expenses",
            rejected
        )
    }

    /**
     * NULL expenseId rows (standalone group expenses) must remain unconstrained —
     * the partial unique index only covers `WHERE expenseId IS NOT NULL`.
     */
    @Test
    fun freshInstall_allowsMultipleNullExpenseIdInGroupExpenses() = runBlocking {
        val groupId = insertGroup()
        val memberId = groupMemberDao.insert(makeMember(groupId, "Payer"))

        val id1 = groupExpenseDao.insert(
            GroupExpense(
                groupId = groupId,
                expenseId = null,
                paidById = memberId,
                date = 1_700_000_000_000L,
                description = "Standalone 1",
                totalAmount = 30.0
            )
        )
        val id2 = groupExpenseDao.insert(
            GroupExpense(
                groupId = groupId,
                expenseId = null,
                paidById = memberId,
                date = 1_700_000_000_000L,
                description = "Standalone 2",
                totalAmount = 20.0
            )
        )

        assertTrue(id1 > 0)
        assertTrue(id2 > 0)
    }
}
