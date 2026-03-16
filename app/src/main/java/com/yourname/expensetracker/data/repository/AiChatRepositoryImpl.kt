package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.AiChatMessageDao
import com.yourname.expensetracker.data.database.dao.AiChatSessionDao
import com.yourname.expensetracker.data.database.entity.AiChatMessageEntity
import com.yourname.expensetracker.data.database.entity.AiChatSessionEntity
import com.yourname.expensetracker.domain.ai.model.AiChatMessage
import com.yourname.expensetracker.domain.ai.model.AiChatSession
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole
import com.yourname.expensetracker.domain.ai.service.AiChatRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiChatRepositoryImpl @Inject constructor(
    private val sessionDao: AiChatSessionDao,
    private val messageDao: AiChatMessageDao,
    private val aiSettingsRepository: AiSettingsRepository,
    private val timeProvider: TimeProvider
) : AiChatRepository {

    override fun observeSessions(): Flow<List<AiChatSession>> =
        sessionDao.observeAll().map { sessions ->
            sessions.map { it.toDomain() }
        }

    override fun observeMessages(sessionId: Long): Flow<List<AiChatMessage>> =
        messageDao.observeBySession(sessionId).map { messages ->
            messages.map { it.toDomain() }
        }

    override suspend fun getSession(sessionId: Long): AiChatSession? =
        sessionDao.getById(sessionId)?.toDomain()

    override suspend fun createSession(title: String?): Long? {
        if (!shouldPersistHistory()) return null

        val now = timeProvider.now()
        return sessionDao.insert(
            AiChatSessionEntity(
                title = title,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    override suspend fun appendMessage(
        sessionId: Long,
        role: AssistantMessageRole,
        kind: AssistantMessageKind,
        text: String,
        payloadJson: String?
    ): Long? {
        if (!shouldPersistHistory()) return null

        val now = timeProvider.now()
        val messageId = messageDao.insert(
            AiChatMessageEntity(
                sessionId = sessionId,
                role = role,
                kind = kind,
                text = text,
                payloadJson = payloadJson,
                createdAt = now
            )
        )
        sessionDao.updateLastTouched(sessionId, now)
        return messageId
    }

    override suspend fun clearSession(sessionId: Long) {
        sessionDao.deleteById(sessionId)
    }

    override suspend fun clearAllHistory() {
        sessionDao.deleteAll()
    }

    private suspend fun shouldPersistHistory(): Boolean =
        aiSettingsRepository.settings().first().storeConversationHistory

    private fun AiChatSessionEntity.toDomain() = AiChatSession(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun AiChatMessageEntity.toDomain() = AiChatMessage(
        id = id,
        sessionId = sessionId,
        role = role,
        kind = kind,
        text = text,
        payloadJson = payloadJson,
        createdAt = createdAt
    )
}
