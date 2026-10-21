package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.AiChatSessionEntity
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AiChatSessionDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: AiChatSessionDao

    @Before
    fun setup() {
        database = AppDatabaseTestFactory.create(ApplicationProvider.getApplicationContext())
        dao = database.aiChatSessionDao()
    }

    @After
    fun teardown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun insert_and_getById_roundTripsSession() = runTest {
        val id = dao.insert(AiChatSessionEntity(title = "Assistant", createdAt = 100L, updatedAt = 100L))

        val result = dao.getById(id)

        assertNotNull(result)
        assertEquals("Assistant", result?.title)
    }

    @Test
    fun observeAll_ordersByUpdatedAtDesc() = runTest {
        dao.insert(AiChatSessionEntity(title = "Older", createdAt = 1L, updatedAt = 10L))
        dao.insert(AiChatSessionEntity(title = "Newer", createdAt = 2L, updatedAt = 20L))

        val result = dao.observeAll().first()

        assertEquals(2, result.size)
        assertEquals("Newer", result.first().title)
    }

    @Test
    fun updateLastTouched_updatesTimestamp() = runTest {
        val id = dao.insert(AiChatSessionEntity(title = "Session", createdAt = 1L, updatedAt = 1L))

        dao.updateLastTouched(id, 99L)

        assertEquals(99L, dao.getById(id)?.updatedAt)
    }

    @Test
    fun deleteById_removesSession() = runTest {
        val id = dao.insert(AiChatSessionEntity(title = "Session", createdAt = 1L, updatedAt = 1L))

        dao.deleteById(id)

        assertNull(dao.getById(id))
    }

    @Test
    fun deleteAll_clearsAllSessions() = runTest {
        dao.insert(AiChatSessionEntity(title = "One", createdAt = 1L, updatedAt = 1L))
        dao.insert(AiChatSessionEntity(title = "Two", createdAt = 2L, updatedAt = 2L))

        dao.deleteAll()

        assertEquals(0, dao.observeAll().first().size)
    }
}
