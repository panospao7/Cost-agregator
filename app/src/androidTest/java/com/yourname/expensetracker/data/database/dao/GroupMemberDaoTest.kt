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

    // ── Fresh-install invariant tests ───────────────────────────────────────────

    /**
     * Fresh installs should expose only Room-declared `group_members` indexes.
     */
    @Test
    fun freshInstall_groupMembers_has_only_room_declared_indexes() = runBlocking {
        val db = database.openHelper.writableDatabase
        val indexes = mutableSetOf<String>()
        db.query("PRAGMA index_list('group_members')").use { cursor ->
            while (cursor.moveToNext()) {
                indexes.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

        assertTrue(indexes.contains("index_group_members_groupId"))
        assertTrue(indexes.contains("index_group_members_groupId_isCurrentUser"))
        assertTrue(indexes.contains("index_group_members_groupId_name"))
        assertFalse(indexes.contains("index_group_members_groupId_currentUser"))
    }

    @Test
    fun setCurrentUser_enforces_single_current_user_without_db_partial_index() = runBlocking {
        val groupId = insertGroup()
        val aliceId = groupMemberDao.insert(makeMember(groupId, "Alice", isCurrentUser = true))
        val bobId = groupMemberDao.insert(makeMember(groupId, "Bob"))

        groupMemberDao.setCurrentUser(groupId, bobId)

        val alice = groupMemberDao.getById(aliceId)
        val bob = groupMemberDao.getById(bobId)
        assertNotNull(alice)
        assertNotNull(bob)
        assertFalse(alice!!.isCurrentUser)
        assertTrue(bob!!.isCurrentUser)
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
     * On a fresh database, `group_expenses` should expose only Room-declared
     * indexes and must not carry the old partial unique index.
     */
    @Test
    fun freshInstall_groupExpenses_has_only_room_declared_indexes() = runBlocking {
        val db = database.openHelper.writableDatabase
        val indexes = mutableSetOf<String>()
        db.query("PRAGMA index_list('group_expenses')").use { cursor ->
            while (cursor.moveToNext()) {
                indexes += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
        }

        assertEquals(
            setOf(
                "index_group_expenses_groupId",
                "index_group_expenses_expenseId",
                "index_group_expenses_paidById",
                "index_group_expenses_groupId_date",
                "index_group_expenses_isReimbursable"
            ),
            indexes
        )
        assertFalse(indexes.contains("index_group_expenses_expenseId_unique"))
    }

    /**
     * Duplicate non-null linked expense IDs are now prevented in app logic,
     * not by a non-Room SQLite partial index.
     */
    @Test
    fun freshInstall_allowsDuplicateNonNullExpenseIdInGroupExpenses_withoutLegacyPartialIndex() = runBlocking {
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

        val secondId = groupExpenseDao.insert(
            GroupExpense(
                groupId = groupId,
                expenseId = expenseId,
                paidById = memberId,
                date = 1_700_000_000_000L,
                description = "Duplicate link",
                totalAmount = 50.0
            )
        )

        assertTrue(secondId > 0)
        val matches = groupExpenseDao.getExpensesForGroupOnce(groupId).filter { it.expenseId == expenseId }
        assertEquals(2, matches.size)
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
