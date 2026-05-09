package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.AiChatMessageDao
import com.yourname.expensetracker.data.database.dao.AiChatSessionDao
import com.yourname.expensetracker.data.database.entity.AiChatMessageEntity
import com.yourname.expensetracker.data.database.entity.AiChatSessionEntity
import com.yourname.expensetracker.domain.ai.model.AiChatMessage
import com.yourname.expensetracker.domain.ai.model.AiChatSession
import com.yourname.expensetracker.domain.ai.model.AssistantHistorySettings
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole
import com.yourname.expensetracker.domain.ai.service.AiChatRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.CloudPayloadRedactor
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiChatRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val sessionDao: AiChatSessionDao,
    private val messageDao: AiChatMessageDao,
    private val aiSettingsRepository: AiSettingsRepository,
    private val timeProvider: TimeProvider,
    private val redactor: CloudPayloadRedactor
) : AiChatRepository {

    /** W34: Controls history persistence mode. Defaults to REDACTED. */
    private var historySettings: AssistantHistorySettings = AssistantHistorySettings.REDACTED

    fun setHistorySettings(settings: AssistantHistorySettings) {
        historySettings = settings
    }

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

        // W34 redaction: strip payloadJson when not in RAW mode
        val storedPayloadJson = if (historySettings.storePayloadJson) payloadJson else null
        // NLP-6: Redact text when in REDACTED mode (not OFF, not RAW)
        val storedText = when (historySettings) {
            AssistantHistorySettings.OFF -> return null
            AssistantHistorySettings.REDACTED -> redactor.redactText(text, CloudPayloadPurpose.DASHBOARD_BRIEFING).text
            AssistantHistorySettings.RAW -> text
        }

        val now = timeProvider.now()
        return database.withTransaction {
            val messageId = messageDao.insert(
                AiChatMessageEntity(
                    sessionId = sessionId,
                    role = role,
                    kind = kind,
                    text = storedText,
                    payloadJson = storedPayloadJson,
                    createdAt = now
                )
            )
            sessionDao.updateLastTouched(sessionId, now)
            messageId
        }
    }

    override suspend fun clearSession(sessionId: Long) {
        sessionDao.deleteById(sessionId)
    }

    override suspend fun clearAllHistory() {
        sessionDao.deleteAll()
    }

    /** W34: Delete messages older than [cutoff] (epoch millis). */
    suspend fun purgeOldMessages(cutoff: Long) {
        messageDao.deleteOlderThan(cutoff)
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
