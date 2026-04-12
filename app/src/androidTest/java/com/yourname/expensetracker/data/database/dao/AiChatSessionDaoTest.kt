package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.AiChatSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiChatSessionDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: AiChatSessionDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.aiChatSessionDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insert_and_getById_roundTripsSession() = runBlocking {
        val id = dao.insert(AiChatSessionEntity(title = "Assistant", createdAt = 100L, updatedAt = 100L))

        val result = dao.getById(id)

        assertNotNull(result)
        assertEquals("Assistant", result?.title)
    }

    @Test
    fun observeAll_ordersByUpdatedAtDesc() = runBlocking {
        dao.insert(AiChatSessionEntity(title = "Older", createdAt = 1L, updatedAt = 10L))
        dao.insert(AiChatSessionEntity(title = "Newer", createdAt = 2L, updatedAt = 20L))

        val result = dao.observeAll().first()

        assertEquals(2, result.size)
        assertEquals("Newer", result.first().title)
    }

    @Test
    fun updateLastTouched_updatesTimestamp() = runBlocking {
        val id = dao.insert(AiChatSessionEntity(title = "Session", createdAt = 1L, updatedAt = 1L))

        dao.updateLastTouched(id, 99L)

        assertEquals(99L, dao.getById(id)?.updatedAt)
    }

    @Test
    fun deleteById_removesSession() = runBlocking {
        val id = dao.insert(AiChatSessionEntity(title = "Session", createdAt = 1L, updatedAt = 1L))

        dao.deleteById(id)

        assertNull(dao.getById(id))
    }

    @Test
    fun deleteAll_clearsAllSessions() = runBlocking {
        dao.insert(AiChatSessionEntity(title = "One", createdAt = 1L, updatedAt = 1L))
        dao.insert(AiChatSessionEntity(title = "Two", createdAt = 2L, updatedAt = 2L))

        dao.deleteAll()

        assertEquals(0, dao.observeAll().first().size)
    }
}
