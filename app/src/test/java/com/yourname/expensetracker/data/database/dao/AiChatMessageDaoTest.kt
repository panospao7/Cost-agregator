package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.AiChatMessageEntity
import com.yourname.expensetracker.data.database.entity.AiChatSessionEntity
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AiChatMessageDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var sessionDao: AiChatSessionDao
    private lateinit var dao: AiChatMessageDao

    @Before
    fun setup() {
        database = AppDatabaseTestFactory.create(ApplicationProvider.getApplicationContext())
        sessionDao = database.aiChatSessionDao()
        dao = database.aiChatMessageDao()
    }

    @After
    fun teardown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun insert_and_getBySession_roundTripsMessage() = runTest {
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
    fun observeBySession_ordersByCreatedAtAscThenId() = runTest {
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
    fun deleteBySession_removesOnlyTargetSessionMessages() = runTest {
        val sessionOne = sessionDao.insert(AiChatSessionEntity(title = "One", createdAt = 1L, updatedAt = 1L))
        val sessionTwo = sessionDao.insert(AiChatSessionEntity(title = "Two", createdAt = 2L, updatedAt = 2L))

        dao.insert(AiChatMessageEntity(sessionId = sessionOne, role = AssistantMessageRole.USER, kind = AssistantMessageKind.QUERY, text = "A", createdAt = 1L))
        dao.insert(AiChatMessageEntity(sessionId = sessionTwo, role = AssistantMessageRole.USER, kind = AssistantMessageKind.QUERY, text = "B", createdAt = 2L))

        dao.deleteBySession(sessionOne)

        assertEquals(0, dao.getBySession(sessionOne).size)
        assertEquals(1, dao.getBySession(sessionTwo).size)
    }

    @Test
    fun deleteAll_clearsMessages() = runTest {
        val sessionId = sessionDao.insert(AiChatSessionEntity(title = "Assistant", createdAt = 1L, updatedAt = 1L))
        dao.insert(AiChatMessageEntity(sessionId = sessionId, role = AssistantMessageRole.USER, kind = AssistantMessageKind.QUERY, text = "A", createdAt = 1L))

        dao.deleteAll()

        assertEquals(0, dao.getBySession(sessionId).size)
    }

    @Test
    fun deletingSession_cascadesMessages() = runTest {
        val sessionId = sessionDao.insert(AiChatSessionEntity(title = "Assistant", createdAt = 1L, updatedAt = 1L))
        dao.insert(AiChatMessageEntity(sessionId = sessionId, role = AssistantMessageRole.USER, kind = AssistantMessageKind.QUERY, text = "A", createdAt = 1L))

        sessionDao.deleteById(sessionId)

        assertEquals(0, dao.getBySession(sessionId).size)
    }
}
