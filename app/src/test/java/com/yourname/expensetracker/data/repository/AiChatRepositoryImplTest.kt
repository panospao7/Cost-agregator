package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.AiChatMessageDao
import com.yourname.expensetracker.data.database.dao.AiChatSessionDao
import com.yourname.expensetracker.data.database.entity.AiChatMessageEntity
import com.yourname.expensetracker.data.database.entity.AiChatSessionEntity
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.privacy.CloudPayloadRedactor
import com.yourname.expensetracker.domain.privacy.RedactedPayload
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AiChatRepositoryImplTest {

    private lateinit var database: AppDatabase
    private lateinit var sessionDao: AiChatSessionDao
    private lateinit var messageDao: AiChatMessageDao
    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var fakeTimeProvider: FakeTimeProvider
    private lateinit var repository: AiChatRepositoryImpl

    @Before
    fun setup() {
        database = mockk(relaxed = true)
        sessionDao = mockk(relaxed = true)
        messageDao = mockk(relaxed = true)
        aiSettingsRepository = mockk()
        fakeTimeProvider = FakeTimeProvider(1_000L)

        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionBlock = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(transactionBlock)) } coAnswers {
            transactionBlock.captured.invoke()
        }

        val redactor = mockk<CloudPayloadRedactor>(relaxed = true)
        every { redactor.redactText(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            RedactedPayload(text = firstArg() as String, redactionApplied = false, fieldsRedacted = emptySet(), payloadHash = "")
        }
        repository = AiChatRepositoryImpl(mockk<DatabaseWriteBarrier>(relaxed = true), database, sessionDao, messageDao, aiSettingsRepository, fakeTimeProvider, redactor)
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `observeSessions maps DAO entities to domain`() = runTest {
        every { sessionDao.observeAll() } returns flowOf(
            listOf(AiChatSessionEntity(id = 1L, title = "Assistant", createdAt = 10L, updatedAt = 20L))
        )

        val result = repository.observeSessions().first()

        assertEquals(1, result.size)
        assertEquals("Assistant", result.first().title)
    }

    @Test
    fun `observeMessages maps DAO entities to domain`() = runTest {
        every { messageDao.observeBySession(1L) } returns flowOf(
            listOf(
                AiChatMessageEntity(
                    id = 2L,
                    sessionId = 1L,
                    role = AssistantMessageRole.USER,
                    kind = AssistantMessageKind.QUERY,
                    text = "How much?",
                    createdAt = 15L
                )
            )
        )

        val result = repository.observeMessages(1L).first()

        assertEquals(1, result.size)
        assertEquals("How much?", result.first().text)
    }

    @Test
    fun `createSession returns null when history disabled`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(storeConversationHistory = false))

        val result = repository.createSession("Assistant")

        assertNull(result)
        coVerify(exactly = 0) { sessionDao.insert(any()) }
    }

    @Test
    fun `createSession inserts when history enabled`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(storeConversationHistory = true))
        coEvery { sessionDao.insert(any()) } returns 5L

        val result = repository.createSession("Assistant")

        assertEquals(5L, result)
        coVerify {
            sessionDao.insert(
                AiChatSessionEntity(title = "Assistant", createdAt = 1_000L, updatedAt = 1_000L)
            )
        }
    }

    @Test
    fun `appendMessage returns null when history disabled`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(storeConversationHistory = false))

        val result = repository.appendMessage(1L, AssistantMessageRole.USER, AssistantMessageKind.QUERY, "Hi")

        assertNull(result)
        coVerify(exactly = 0) { messageDao.insert(any()) }
    }

    @Test
    fun `appendMessage inserts message and updates session timestamp when history enabled`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(storeConversationHistory = true))
        coEvery { messageDao.insert(any()) } returns 9L

        val result = repository.appendMessage(
            sessionId = 7L,
            role = AssistantMessageRole.ASSISTANT,
            kind = AssistantMessageKind.RESULT,
            text = "42.00 EUR",
            payloadJson = "{}"
        )

        assertEquals(9L, result)
        coVerify {
            messageDao.insert(
                AiChatMessageEntity(
                    sessionId = 7L,
                    role = AssistantMessageRole.ASSISTANT,
                    kind = AssistantMessageKind.RESULT,
                    text = "42.00 EUR",
                    payloadJson = "{}",
                    createdAt = 1_000L
                )
            )
        }
        coVerify { sessionDao.updateLastTouched(7L, 1_000L) }
    }

    @Test
    fun `clearSession delegates to session dao`() = runTest {
        repository.clearSession(3L)
        coVerify { sessionDao.deleteById(3L) }
    }

    @Test
    fun `clearAllHistory delegates to session dao`() = runTest {
        repository.clearAllHistory()
        coVerify { sessionDao.deleteAll() }
    }
}
