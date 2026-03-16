package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.AiChatMessageEntity
import com.yourname.expensetracker.data.database.entity.AiChatSessionEntity
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiChatMessageDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var sessionDao: AiChatSessionDao
    private lateinit var dao: AiChatMessageDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        sessionDao = database.aiChatSessionDao()
        dao = database.aiChatMessageDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insert_and_getBySession_roundTripsMessage() = runBlocking {
        val sessionId = sessionDao.insert(AiChatSessionEntity(title = "Assistant", createdAt = 1L, updatedAt = 1L))
        dao.insert(
            AiChatMessageEntity(
                sessionId = sessionId,
                role = AssistantMessageRole.USER,
                kind = AssistantMessageKind.QUERY,
                text = "How much did I spend?",
                createdAt = 2L
            )
        )

        val result = dao.getBySession(sessionId)

        assertEquals(1, result.size)
        assertEquals("How much did I spend?", result.first().text)
    }

    @Test
    fun observeBySession_ordersByCreatedAtAscThenId() = runBlocking {
        val sessionId = sessionDao.insert(AiChatSessionEntity(title = "Assistant", createdAt = 1L, updatedAt = 1L))
        dao.insert(
            AiChatMessageEntity(
                sessionId = sessionId,
                role = AssistantMessageRole.USER,
                kind = AssistantMessageKind.QUERY,
                text = "Second",
                createdAt = 20L
            )
        )
        dao.insert(
            AiChatMessageEntity(
                sessionId = sessionId,
                role = AssistantMessageRole.ASSISTANT,
                kind = AssistantMessageKind.RESULT,
                text = "First",
                createdAt = 10L
            )
        )

        val result = dao.observeBySession(sessionId).first()

        assertEquals(listOf("First", "Second"), result.map { it.text })
    }

    @Test
    fun deleteBySession_removesOnlyTargetSessionMessages() = runBlocking {
        val sessionOne = sessionDao.insert(AiChatSessionEntity(title = "One", createdAt = 1L, updatedAt = 1L))
        val sessionTwo = sessionDao.insert(AiChatSessionEntity(title = "Two", createdAt = 2L, updatedAt = 2L))

        dao.insert(AiChatMessageEntity(sessionId = sessionOne, role = AssistantMessageRole.USER, kind = AssistantMessageKind.QUERY, text = "A", createdAt = 1L))
        dao.insert(AiChatMessageEntity(sessionId = sessionTwo, role = AssistantMessageRole.USER, kind = AssistantMessageKind.QUERY, text = "B", createdAt = 2L))

        dao.deleteBySession(sessionOne)

        assertEquals(0, dao.getBySession(sessionOne).size)
        assertEquals(1, dao.getBySession(sessionTwo).size)
    }

    @Test
    fun deleteAll_clearsMessages() = runBlocking {
        val sessionId = sessionDao.insert(AiChatSessionEntity(title = "Assistant", createdAt = 1L, updatedAt = 1L))
        dao.insert(AiChatMessageEntity(sessionId = sessionId, role = AssistantMessageRole.USER, kind = AssistantMessageKind.QUERY, text = "A", createdAt = 1L))

        dao.deleteAll()

        assertEquals(0, dao.getBySession(sessionId).size)
    }

    @Test
    fun deletingSession_cascadesMessages() = runBlocking {
        val sessionId = sessionDao.insert(AiChatSessionEntity(title = "Assistant", createdAt = 1L, updatedAt = 1L))
        dao.insert(AiChatMessageEntity(sessionId = sessionId, role = AssistantMessageRole.USER, kind = AssistantMessageKind.QUERY, text = "A", createdAt = 1L))

        sessionDao.deleteById(sessionId)

        assertEquals(0, dao.getBySession(sessionId).size)
    }
}
