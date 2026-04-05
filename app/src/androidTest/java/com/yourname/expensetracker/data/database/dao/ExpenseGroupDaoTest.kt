package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupMember
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpenseGroupDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var expenseGroupDao: ExpenseGroupDao
    private lateinit var groupMemberDao: GroupMemberDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        expenseGroupDao = database.expenseGroupDao()
        groupMemberDao = database.groupMemberDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun makeGroup(
        name: String = "Weekend Trip",
        isActive: Boolean = true,
        createdAt: Long = System.currentTimeMillis()
    ) = ExpenseGroup(
        name = name,
        description = "Test group",
        isActive = isActive,
        createdAt = createdAt
    )

    private fun makeMember(groupId: Long, name: String = "Alice") = GroupMember(
        groupId = groupId,
        name = name,
        email = "$name@test.com"
    )

    @Test
    fun insertGroup_retrieveById() = runBlocking {
        val group = makeGroup(name = "Trip to Paris")
        val id = expenseGroupDao.insert(group)

        assertTrue(id > 0)

        val loaded = expenseGroupDao.getById(id)
        assertNotNull(loaded)
        assertEquals(id, loaded!!.id)
        assertEquals("Trip to Paris", loaded.name)
        assertTrue(loaded.isActive)
    }

    @Test
    fun queryActiveGroups_returnsOnlyActive() = runBlocking {
        val base = 1_700_000_000_000L
        expenseGroupDao.insert(makeGroup(name = "Inactive", isActive = false, createdAt = base))
        expenseGroupDao.insert(makeGroup(name = "Active 1", isActive = true, createdAt = base + 1))
        expenseGroupDao.insert(makeGroup(name = "Active 2", isActive = true, createdAt = base + 2))

        val activeGroups = expenseGroupDao.getActive()

        assertEquals(2, activeGroups.size)
        assertTrue(activeGroups.all { it.isActive })
        assertEquals(listOf("Active 2", "Active 1"), activeGroups.map { it.name })
    }

    @Test
    fun archiveRestoreGroup_togglesActiveStatus() = runBlocking {
        val groupId = expenseGroupDao.insert(makeGroup(name = "Archivable"))

        expenseGroupDao.archiveGroup(groupId)
        val archived = expenseGroupDao.getById(groupId)
        assertNotNull(archived)
        assertFalse(archived!!.isActive)

        expenseGroupDao.restoreGroup(groupId)
        val restored = expenseGroupDao.getById(groupId)
        assertNotNull(restored)
        assertTrue(restored!!.isActive)
    }

    @Test
    fun deleteGroupWithMembers_cascadesMembers() = runBlocking {
        val groupId = expenseGroupDao.insert(makeGroup(name = "Roommates"))
        groupMemberDao.insert(makeMember(groupId, "Alice"))
        groupMemberDao.insert(makeMember(groupId, "Bob"))

        val group = expenseGroupDao.getById(groupId)
        assertNotNull(group)

        expenseGroupDao.delete(group!!)

        assertNull(expenseGroupDao.getById(groupId))
        assertEquals(0, groupMemberDao.getMemberCount(groupId))
    }
}
