package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
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
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

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
}
